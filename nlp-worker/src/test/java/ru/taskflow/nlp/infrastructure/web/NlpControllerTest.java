package ru.taskflow.nlp.infrastructure.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.taskflow.nlp.application.NlpService;
import ru.taskflow.nlp.application.TranscriptionService;
import ru.taskflow.nlp.domain.ToolCallProvider;
import ru.taskflow.nlp.domain.ToolCallResult;
import ru.taskflow.nlp.infrastructure.config.ToolCallProvidersUnavailableException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NlpControllerTest {

    private final ToolCallProvider provider = mock(ToolCallProvider.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new NlpController(
                mock(NlpService.class), provider, mock(TranscriptionService.class))).build();
    }

    @Test
    void toolCall_returnsServiceUnavailableOnProviderFailure() throws Exception {
        when(provider.call(any())).thenThrow(new ToolCallProvidersUnavailableException());

        mvc.perform(post("/nlp/tool-call")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[],\"tools\":[]}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().json("""
                        {"type":"about:blank","title":"Service Unavailable","status":503,
                         "detail":"Поставщики модели недоступны","instance":"/nlp/tool-call"}
                        """, true));
    }

    @Test
    void toolCall_keepsLegitimateEmptyResponseSuccessful() throws Exception {
        when(provider.call(any())).thenReturn(ToolCallResult.empty());

        mvc.perform(post("/nlp/tool-call")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[],\"tools\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolCalls").isEmpty());
    }
}
