package ru.taskflow.shared.resources;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class RedisResourceGateTest {
    @Container static GenericContainer<?> server=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory factory;
    StringRedisTemplate redis; ResourcePolicy policy; RedisResourceGate gate;
    @BeforeAll static void connect(){factory=new LettuceConnectionFactory(server.getHost(),server.getMappedPort(6379));factory.afterPropertiesSet();factory.start();}
    @AfterAll static void close(){factory.destroy();}
    @BeforeEach void reset(){
        redis=new StringRedisTemplate(factory);redis.afterPropertiesSet();
        try(var c=factory.getConnection()){c.serverCommands().flushDb();}
        policy=new ResourcePolicy();gate=new RedisResourceGate(redis,policy,new SimpleMeterRegistry());
    }
    ModelRequestContext.Scope context(UUID user){return ModelRequestContext.open(new ModelRequestContext(user,System.currentTimeMillis()+35000,1));}
    @Test void replicaLimitsAndUserFairnessAreShared(){
        var other=new RedisResourceGate(redis,policy,new SimpleMeterRegistry());UUID user=UUID.randomUUID();
        try(var first=gate.acquire("external",user,2,1)){
            assertThatThrownBy(()->other.acquire("external",user,2,1)).isInstanceOf(ResourceRejectedException.class);
            try(var second=other.acquire("external",UUID.randomUUID(),2,1)){
                assertThatThrownBy(()->gate.acquire("external",UUID.randomUUID(),2,1)).isInstanceOf(ResourceRejectedException.class);
            }
        }
        try(var reused=other.acquire("external",user,2,1)){assertThat(reused).isNotNull();}
    }
    @Test void budgetCannotBeOverspentByParallelReservations() throws Exception {
        policy.setGlobalDailyTokens(1000);policy.setUserDailyTokens(1000);
        var start=new CountDownLatch(1);var results=new ArrayList<Future<Boolean>>();
        try(var executor=Executors.newFixedThreadPool(12)){
            for(int i=0;i<30;i++) results.add(executor.submit(()->{
                start.await();try(var ctx=context(UUID.randomUUID())){gate.reserve("tokens",200);return true;}
                catch(ResourceRejectedException rejected){return false;}
            }));
            start.countDown();int accepted=0;for(var f:results)if(f.get(10,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(5);
        }
    }
    @Test void unknownUsageKeepsReservationAndSettlementOnlyRefundsOnce(){
        policy.setGlobalDailyTokens(100);policy.setUserDailyTokens(100);UUID user=UUID.randomUUID();
        try(var ctx=context(user)){
            var reserve=gate.reserve("tokens",90);reserve.settle(0);
            assertThatThrownBy(()->gate.reserve("tokens",11)).isInstanceOf(ResourceRejectedException.class);
            reserve.settle(30);reserve.settle(30);
            gate.reserve("tokens",70);
            assertThatThrownBy(()->gate.reserve("tokens",1)).isInstanceOf(ResourceRejectedException.class);
        }
    }
    @Test void personalAudioBudgetIsIndependentOfModelBudget(){
        policy.setUserDailyAudioSeconds(2);UUID user=UUID.randomUUID();
        try(var ctx=context(user)){
            gate.reserve("audio",2).settle(2);
            assertThatThrownBy(()->gate.reserve("audio",1)).isInstanceOf(ResourceRejectedException.class);
            gate.reserve("tokens",20).settle(20);
        }
    }
    @Test void unavailableRedisDoesNotGrantPermitOrBudget(){
        var unavailable=org.mockito.Mockito.mock(StringRedisTemplate.class);
        var rejecting=new RedisResourceGate(unavailable,policy,new SimpleMeterRegistry());
        try(var ctx=context(UUID.randomUUID())){
            assertThatThrownBy(()->rejecting.reserve("tokens",10)).isInstanceOf(ResourceRejectedException.class);
            assertThatThrownBy(()->rejecting.acquire("worker",UUID.randomUUID(),4,1)).isInstanceOf(ResourceRejectedException.class);
        }
    }
    @Test void expiredHolderCannotReleaseNewOwnersSlot(){
        UUID user=UUID.randomUUID();var first=gate.acquire("worker",user,1,1);
        for(String key:redis.keys("taskflow:resources:{model}:worker:*"))
            for(String token:redis.opsForZSet().range(key,0,-1))redis.opsForZSet().add(key,token,0);
        try(var second=gate.acquire("worker",user,1,1)){
            first.close();
            assertThatThrownBy(()->gate.acquire("worker",user,1,1)).isInstanceOf(ResourceRejectedException.class);
        }
    }
}
