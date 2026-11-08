package ru.taskflow.shared.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.*;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
public class GuardedProviderInterceptor implements ClientHttpRequestInterceptor {
    private final RedisResourceGate gate; private final ResourcePolicy policy;
    private final ObjectMapper json; private final MeterRegistry meters;
    public GuardedProviderInterceptor(RedisResourceGate gate,ResourcePolicy policy,ObjectMapper json,MeterRegistry meters) {
        this.gate=gate;this.policy=policy;this.json=json;this.meters=meters;
    }
    @Override public ClientHttpResponse intercept(HttpRequest request,byte[] body,ClientHttpRequestExecution execution) throws IOException {
        if(request.getMethod()==org.springframework.http.HttpMethod.GET && request.getURI().getPath().endsWith("/models"))
            return execution.execute(request,body);
        var context=ModelRequestContext.required();
        boolean audio=request.getURI().getPath().endsWith("/audio/transcriptions");
        if(!audio) {
            if(body.length>policy.getMaxInputBytes())
                throw new ResourceRejectedException(HttpStatus.PAYLOAD_TOO_LARGE,"Слишком большой контекст модели");
            var payload=(ObjectNode)json.readTree(body);
            if(payload.has("completionOptions"))
                ((ObjectNode)payload.get("completionOptions")).put("maxTokens",""+policy.getMaxOutputTokens());
            else {payload.remove("max_tokens"); payload.put("max_completion_tokens",policy.getMaxOutputTokens());}
            body=json.writeValueAsBytes(payload);
            request.getHeaders().setContentLength(body.length);
        }
        long audioUnits=Math.max(context.audioSeconds(),policy.getMinBillableAudioSeconds());
        try(var lease=gate.acquire("external",context.userId(),policy.getWorkerConcurrent(),policy.getUserConcurrent())) {
            var reservation=gate.reserve(audio?"audio":"tokens",audio?audioUnits:
                    (long)body.length+4096+policy.getMaxOutputTokens());
            context.remainingMillis();
            var timer=meters.timer("model_provider_duration","kind",audio?"audio":"tokens","provider",request.getURI().getHost());
            long started=System.nanoTime();
            boolean accounted=false;
            try {
                meters.counter("model_provider_attempts","kind",audio?"audio":"tokens","provider",request.getURI().getHost()).increment();
                var response=execution.execute(request,body);
                if(response.getStatusCode().isError())
                    meters.counter("model_provider_failures","provider",request.getURI().getHost(),"kind",audio?"audio":"tokens").increment();
                if(response.getStatusCode().is2xxSuccessful()) {
                    if(audio) {
                        reservation.settle(audioUnits);accounted=true;
                        meters.counter("model_provider_audio_seconds","provider",request.getURI().getHost()).increment(audioUnits);
                    }
                    else {
                        var usage=json.readTree(response.getBody()).path("usage");
                        long input=usage.path("prompt_tokens").asLong(-1),output=usage.path("completion_tokens").asLong(-1);
                        if(input>=0 && output>=0 && input+output>0) {
                            reservation.settle(input+output);accounted=true;
                            meters.counter("model_provider_tokens","provider",request.getURI().getHost(),"direction","input").increment(input);
                            meters.counter("model_provider_tokens","provider",request.getURI().getHost(),"direction","output").increment(output);
                        }
                    }
                }
                return response;
            } catch(IOException | RuntimeException exception) {
                meters.counter("model_provider_failures","provider",request.getURI().getHost(),"kind",audio?"audio":"tokens").increment();
                throw exception;
            } finally {
                if(!accounted) meters.counter("model_resource_unknown_usage","kind",audio?"audio":"tokens").increment();
                timer.record(System.nanoTime()-started,java.util.concurrent.TimeUnit.NANOSECONDS);
            }
        }
    }
}
