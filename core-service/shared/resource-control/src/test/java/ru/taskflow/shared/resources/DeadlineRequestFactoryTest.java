package ru.taskflow.shared.resources;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;

class DeadlineRequestFactoryTest {
    @Test void deadlineIncludesBodyEvenWhenServerKeepsSendingBytes() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            server.setExecutor(executor);
            server.createContext("/slow",exchange->{
                try(exchange){exchange.sendResponseHeaders(200,100);
                    for(int i=0;i<100;i++){exchange.getResponseBody().write(1);exchange.getResponseBody().flush();
                        try{Thread.sleep(25);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}
                }catch(IOException ignored){}
            });server.start();
            long started=System.nanoTime();
            try(var ctx=ModelRequestContext.open(new ModelRequestContext(UUID.randomUUID(),System.currentTimeMillis()+120,0))){
                var request=new DeadlineRequestFactory(5000).createRequest(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/slow"),HttpMethod.GET);
                assertThatThrownBy(request::execute).isInstanceOf(IOException.class);
            } finally {server.stop(0);}
            assertThat((System.nanoTime()-started)/1_000_000).isLessThan(1500);
        }
    }
    @Test void expiredDeadlineDoesNotStartAnHttpRequest(){
        try(var ctx=ModelRequestContext.open(new ModelRequestContext(UUID.randomUUID(),System.currentTimeMillis()-1,0))){
            var request=new DeadlineRequestFactory(1000).createRequest(URI.create("http://127.0.0.1:1"),HttpMethod.GET);
            assertThatThrownBy(request::execute).isInstanceOf(ResourceRejectedException.class);
        }
    }
    @Test void responseHasAHardSizeLimit() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/large",exchange->{try(exchange){exchange.sendResponseHeaders(200,3_000_000);exchange.getResponseBody().write(new byte[3_000_000]);}catch(IOException ignored){}});
        server.start();try {
            var request=new DeadlineRequestFactory(5000).createRequest(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/large"),HttpMethod.GET);
            assertThatThrownBy(request::execute).isInstanceOf(IOException.class);
        }finally{server.stop(0);}
    }
}
