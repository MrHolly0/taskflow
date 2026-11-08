package ru.taskflow.shared.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ProviderGuardTest {
    @Test void allModesGetOutputCapAndUsageSettlementWithoutConsumingResponse() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var cap=new AtomicInteger();
        server.createContext("/chat/completions",exchange->{try(exchange){
            var request=new ObjectMapper().readTree(exchange.getRequestBody());cap.set(request.path("max_completion_tokens").asInt());
            byte[] bytes="{\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":10},\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes();
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }});server.start();
        var gate=mock(RedisResourceGate.class);var reservation=mock(RedisResourceGate.Reservation.class);
        when(gate.acquire(anyString(),any(),anyInt(),anyInt())).thenReturn(mock(RedisResourceGate.Lease.class));
        when(gate.reserve(eq("tokens"),anyLong())).thenReturn(reservation);
        var policy=new ResourcePolicy();var guard=new GuardedProviderInterceptor(gate,policy,new ObjectMapper(),new SimpleMeterRegistry());
        try(var ctx=ModelRequestContext.open(new ModelRequestContext(UUID.randomUUID(),System.currentTimeMillis()+5000,0))){
            var client=RestClient.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort())
                    .requestFactory(new DeadlineRequestFactory(5000)).requestInterceptor(guard).build();
            String response=client.post().uri("/chat/completions").body(Map.of("messages",List.of(),"max_completion_tokens",99999))
                    .retrieve().body(String.class);
            assertThat(response).contains("choices");assertThat(cap).hasValue(3000);verify(reservation).settle(30);
        }finally{server.stop(0);}
    }
    @Test void quotaFailureDoesNotStartExternalHttpWork() {
        var gate=mock(RedisResourceGate.class);when(gate.acquire(anyString(),any(),anyInt(),anyInt()))
                .thenThrow(ResourceRejectedException.unavailable());
        var policy=new ResourcePolicy();var client=RestClient.builder().baseUrl("http://127.0.0.1:1")
                .requestFactory(new DeadlineRequestFactory(1000))
                .requestInterceptor(new GuardedProviderInterceptor(gate,policy,new ObjectMapper(),new SimpleMeterRegistry())).build();
        try(var ctx=ModelRequestContext.open(new ModelRequestContext(UUID.randomUUID(),System.currentTimeMillis()+5000,0))){
            assertThatThrownBy(()->client.post().uri("/chat/completions").body(Map.of("messages",List.of()))
                    .retrieve().body(String.class)).isInstanceOf(ResourceRejectedException.class);
            verify(gate,never()).reserve(anyString(),anyLong());
        }
    }

    @Test void shortAudioReservesTheProvidersMinimumBilledDuration() throws Exception {
        var gate=mock(RedisResourceGate.class);var reservation=mock(RedisResourceGate.Reservation.class);
        when(gate.acquire(anyString(),any(),anyInt(),anyInt())).thenReturn(mock(RedisResourceGate.Lease.class));
        when(gate.reserve(eq("audio"),anyLong())).thenReturn(reservation);
        var request=new org.springframework.mock.http.client.MockClientHttpRequest(
                org.springframework.http.HttpMethod.POST,java.net.URI.create("http://provider/audio/transcriptions"));
        var execution=mock(org.springframework.http.client.ClientHttpRequestExecution.class);
        when(execution.execute(any(),any())).thenReturn(new org.springframework.mock.http.client.MockClientHttpResponse(
                "ok".getBytes(),org.springframework.http.HttpStatus.OK));
        try(var ctx=ModelRequestContext.open(new ModelRequestContext(UUID.randomUUID(),System.currentTimeMillis()+5000,1))){
            new GuardedProviderInterceptor(gate,new ResourcePolicy(),new ObjectMapper(),new SimpleMeterRegistry())
                    .intercept(request,new byte[]{1},execution);
        }
        verify(gate).reserve("audio",10);verify(reservation).settle(10);
    }
}
