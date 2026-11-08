package ru.taskflow.nlp.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import ru.taskflow.shared.resources.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkerResourceFilterTest {
    ResourcePolicy policy(){var p=new ResourcePolicy();p.setInternalSecret("s".repeat(32));return p;}
    @Test void missingSecretIsRejectedBeforeAdmissionAndContextIsNotTrusted() throws Exception {
        var gate=mock(RedisResourceGate.class);var filter=new WorkerResourceFilter(policy(),gate,new ObjectMapper());
        var req=new MockHttpServletRequest("POST","/nlp/tool-call");req.addHeader("X-Model-User",UUID.randomUUID());
        var response=new MockHttpServletResponse();filter.doFilter(req,response,(a,b)->{throw new AssertionError("controller called");});
        assertThat(response.getStatus()).isEqualTo(403);verifyNoInteractions(gate);assertThat(ModelRequestContext.current()).isNull();
    }
    @Test void deadlineAndUserTravelInsideScopedAdmissionOnly() throws Exception {
        var gate=mock(RedisResourceGate.class);var filter=new WorkerResourceFilter(policy(),gate,new ObjectMapper());
        when(gate.acquire(anyString(),any(),anyInt(),anyInt())).thenReturn(mock(RedisResourceGate.Lease.class));
        UUID user=UUID.randomUUID();var req=new MockHttpServletRequest("POST","/nlp/tool-call");
        req.addHeader("X-Model-Secret","s".repeat(32));req.addHeader("X-Model-User",user.toString());
        long deadline=System.currentTimeMillis()+1000;req.addHeader("X-Model-Deadline",""+deadline);
        filter.doFilter(req,new MockHttpServletResponse(),(a,b)->{
            assertThat(ModelRequestContext.required().userId()).isEqualTo(user);
            assertThat(ModelRequestContext.required().deadlineMillis()).isEqualTo(deadline);
        });assertThat(ModelRequestContext.current()).isNull();
    }
    @Test void expiredRequestNeverReachesWorker() throws Exception {
        var gate=mock(RedisResourceGate.class);var req=new MockHttpServletRequest("POST","/nlp/tool-call");
        req.addHeader("X-Model-Secret","s".repeat(32));req.addHeader("X-Model-User",UUID.randomUUID().toString());req.addHeader("X-Model-Deadline","1");
        var response=new MockHttpServletResponse();new WorkerResourceFilter(policy(),gate,new ObjectMapper()).doFilter(req,response,(a,b)->{throw new AssertionError();});
        assertThat(response.getStatus()).isEqualTo(504);verifyNoInteractions(gate);
    }
    @Test void emptyDeploymentSecretFailsStartup() {
        assertThatThrownBy(()->new WorkerResourceFilter(new ResourcePolicy(),mock(RedisResourceGate.class),new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class);
    }
}
