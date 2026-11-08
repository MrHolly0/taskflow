package ru.taskflow.shared.resources;

import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.*;
import java.io.IOException;

public class InternalWorkerInterceptor implements ClientHttpRequestInterceptor {
    private final ResourcePolicy policy;
    public InternalWorkerInterceptor(ResourcePolicy policy) {this.policy=policy;}
    @Override public ClientHttpResponse intercept(HttpRequest request,byte[] body,ClientHttpRequestExecution execution) throws IOException {
        var context=ModelRequestContext.required();
        if(policy.getInternalSecret().length()<32) throw ResourceRejectedException.unavailable();
        request.getHeaders().set("X-Model-Secret",policy.getInternalSecret());
        request.getHeaders().set("X-Model-User",context.userId().toString());
        request.getHeaders().set("X-Model-Deadline",""+context.deadlineMillis());
        var response=execution.execute(request,body);
        if("true".equals(response.getHeaders().getFirst("X-Resource-Rejected"))) {
            int status=response.getStatusCode().value();
            String retry=response.getHeaders().getFirst("Retry-After");
            long seconds=5;
            try { if(retry!=null) seconds=Long.parseLong(retry); } catch(NumberFormatException ignored) { }
            response.close();
            throw new ResourceRejectedException(HttpStatus.valueOf(status),
                    status==429?"Дневной лимит обработки исчерпан":"Обработка временно ограничена, попробуй позже",seconds);
        }
        return response;
    }
}
