package ru.taskflow.shared.resources;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RedisResourceGate {
    private static final Logger log = LoggerFactory.getLogger(RedisResourceGate.class);
    private static final String PREFIX = "taskflow:resources:{model}:";
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local t = redis.call('TIME'); local now = t[1]*1000 + math.floor(t[2]/1000)
            for i=1,2 do redis.call('ZREMRANGEBYSCORE',KEYS[i],'-inf',now) end
            if redis.call('ZCARD',KEYS[1]) >= tonumber(ARGV[2]) then return 0 end
            if redis.call('ZCARD',KEYS[2]) >= tonumber(ARGV[3]) then return 0 end
            for i=1,2 do
                redis.call('ZADD',KEYS[i],now+tonumber(ARGV[4]),ARGV[1])
                redis.call('PEXPIRE',KEYS[i],tonumber(ARGV[4])*2)
            end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            for i=1,2 do redis.call('ZREM',KEYS[i],ARGV[1]) end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local t=redis.call('TIME'); local day=math.floor(t[1]/86400)
            local amount=tonumber(ARGV[1])
            for i=1,2 do
                local used=tonumber(redis.call('HGET',KEYS[i],day) or '0')
                if used+amount > tonumber(ARGV[i+1]) then return -1 end
            end
            for i=1,2 do
                for _,field in ipairs(redis.call('HKEYS',KEYS[i])) do
                    if tonumber(field) < day-1 then redis.call('HDEL',KEYS[i],field) end
                end
                redis.call('HINCRBY',KEYS[i],day,amount); redis.call('EXPIRE',KEYS[i],259200)
            end
            redis.call('SET',KEYS[3],amount,'EX',172800)
            return day
            """, Long.class);
    private static final DefaultRedisScript<Long> SETTLE = new DefaultRedisScript<>("""
            local amount=redis.call('GET',KEYS[3]); if not amount then return 0 end
            local delta=tonumber(ARGV[2])-tonumber(amount)
            for i=1,2 do redis.call('HINCRBY',KEYS[i],ARGV[1],delta) end
            redis.call('DEL',KEYS[3]); return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final ResourcePolicy policy;
    private final MeterRegistry meters;
    private final java.util.concurrent.ConcurrentMap<String,AtomicInteger> inFlight = new java.util.concurrent.ConcurrentHashMap<>();
    public RedisResourceGate(StringRedisTemplate redis, ResourcePolicy policy, MeterRegistry meters) {
        this.redis=redis; this.policy=policy; this.meters=meters;
        if (policy.getLeaseMs() <= policy.getOperationTimeoutMs() + policy.getExternalTimeoutMs())
            throw new IllegalArgumentException("lease-ms должен превышать общий дедлайн и таймаут внешнего вызова");
    }
    public Lease acquire(String pool, UUID user, int globalLimit, int userLimit) {
        if (globalLimit < 1 || userLimit < 1) throw ResourceRejectedException.unavailable();
        String token=UUID.randomUUID().toString();
        List<String> keys=List.of(PREFIX+pool+":all",PREFIX+pool+":user:"+user);
        Long result=execute(ACQUIRE, keys, token, ""+globalLimit, ""+userLimit, ""+policy.getLeaseMs());
        if (result != 1) {
            meters.counter("model_resource_rejected", "reason", "busy").increment();
            throw new ResourceRejectedException(HttpStatus.SERVICE_UNAVAILABLE, "Ассистент занят, попробуй чуть позже");
        }
        var counter=inFlight.computeIfAbsent(pool, key -> meters.gauge("model_resource_inflight",
                io.micrometer.core.instrument.Tags.of("pool",key),new AtomicInteger()));
        counter.incrementAndGet();
        return new Lease(keys,token,counter);
    }
    public Reservation reserve(String kind, long amount) {
        UUID user=ModelRequestContext.required().userId();
        long global="tokens".equals(kind)?policy.getGlobalDailyTokens():policy.getGlobalDailyAudioSeconds();
        long personal="tokens".equals(kind)?policy.getUserDailyTokens():policy.getUserDailyAudioSeconds();
        if (amount < 1 || global < 1 || personal < 1) throw ResourceRejectedException.unavailable();
        List<String> keys=List.of(PREFIX+"budget:"+kind+":all", PREFIX+"budget:"+kind+":user:"+user,
                PREFIX+"reservation:"+UUID.randomUUID());
        Long day=execute(RESERVE,keys,""+amount,""+global,""+personal);
        if(day < 0) {
            meters.counter("model_resource_rejected","reason","budget").increment();
            throw new ResourceRejectedException(HttpStatus.TOO_MANY_REQUESTS, "Дневной лимит обработки исчерпан",
                    86400-Math.floorMod(System.currentTimeMillis()/1000,86400));
        }
        meters.counter("model_resource_reserved", "kind",kind).increment(amount);
        return new Reservation(keys,day,amount,kind);
    }
    private Long execute(DefaultRedisScript<Long> script, List<String> keys, String... args) {
        try {
            Long value=redis.execute(script,keys,(Object[])args);
            if(value == null) throw ResourceRejectedException.unavailable();
            return value;
        } catch (ResourceRejectedException exception) { throw exception;
        } catch (RuntimeException exception) {
            meters.counter("model_resource_rejected","reason","storage").increment();
            throw ResourceRejectedException.unavailable();
        }
    }
    public final class Lease implements AutoCloseable {
        private final List<String> keys; private final String token; private final AtomicInteger counter; private boolean closed;
        private Lease(List<String> keys,String token,AtomicInteger counter) { this.keys=keys;this.token=token;this.counter=counter; }
        @Override public void close() {
            if(closed)return; closed=true; counter.decrementAndGet();
            try { execute(RELEASE,keys,token); }
            catch(RuntimeException exception) { log.warn("Не удалось освободить слот; он истечёт по lease"); }
        }
    }
    public final class Reservation {
        private final List<String> keys; private final long day,amount; private final String kind;
        private Reservation(List<String> keys,long day,long amount,String kind) {
            this.keys=keys;this.day=day;this.amount=amount;this.kind=kind;
        }
        public void settle(long actual) {
            if(actual < 1) return; // Неизвестный usage оставляет полный резерв.
            if(actual > amount) meters.counter("model_resource_reservation_overrun","kind",kind).increment();
            try {
                if(execute(SETTLE,keys,""+day,""+actual)==1)
                    meters.counter("model_resource_used","kind",kind).increment(actual);
            } catch(RuntimeException exception) { log.warn("Расход не сверён; полный резерв сохранён"); }
        }
    }
}
