package ru.taskflow.shared.resources;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

public class DeadlineRequestFactory implements ClientHttpRequestFactory {
    private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final long maxMillis;
    public DeadlineRequestFactory(long maxMillis) { this.maxMillis=maxMillis; }
    @Override public ClientHttpRequest createRequest(URI uri,HttpMethod method) {
        return new ClientHttpRequest() {
            private final HttpHeaders headers=new HttpHeaders();
            private final ByteArrayOutputStream output=new ByteArrayOutputStream();
            @Override public HttpHeaders getHeaders(){return headers;}
            @Override public OutputStream getBody(){return output;}
            @Override public ClientHttpResponse execute() throws IOException {return executeInternal(headers,output.toByteArray());}
            @Override public HttpMethod getMethod() {return method;}
            @Override public URI getURI() {return uri;}
            private ClientHttpResponse executeInternal(HttpHeaders headers,byte[] body) throws IOException {
                long remaining=ModelRequestContext.current()==null?maxMillis:
                        Math.min(maxMillis,ModelRequestContext.required().remainingMillis());
                var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(remaining));
                headers.forEach((key,values)-> {
                    if(!List.of("content-length","host","connection","expect","upgrade").contains(key.toLowerCase(java.util.Locale.ROOT)))
                        values.forEach(value->builder.header(key,value));
                });
                var request=builder.method(method.name(),HttpRequest.BodyPublishers.ofByteArray(body)).build();
                var future=CLIENT.sendAsync(request,ignored->new LimitedBody());
                try {
                    var response=future.get(remaining,TimeUnit.MILLISECONDS);
                    HttpHeaders responseHeaders=new HttpHeaders();
                    response.headers().map().forEach(responseHeaders::put);
                    return new ClientHttpResponse() {
                        @Override public HttpStatusCode getStatusCode(){return HttpStatusCode.valueOf(response.statusCode());}
                        @Override public String getStatusText(){return "";}
                        @Override public HttpHeaders getHeaders(){return responseHeaders;}
                        @Override public InputStream getBody(){return new ByteArrayInputStream(response.body());}
                        @Override public void close() { }
                    };
                } catch(InterruptedException exception) {
                    future.cancel(true); Thread.currentThread().interrupt(); throw new IOException("Запрос прерван",exception);
                } catch(TimeoutException exception) {
                    future.cancel(true); throw new IOException("Время внешнего запроса истекло",exception);
                } catch(ExecutionException exception) { throw new IOException("Внешний запрос не выполнен",exception.getCause()); }
            }
        };
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private static final int MAX=2*1024*1024;
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream output=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public void onSubscribe(Flow.Subscription value){subscription=value; value.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if(output.size()+buffer.remaining()>MAX) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Ответ превышает допустимый размер"));return;
                }
                byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error){result.completeExceptionally(error);}
        @Override public void onComplete(){result.complete(output.toByteArray());}
    }
}
