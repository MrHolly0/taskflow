package ru.taskflow.shared.widgets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.zip.Deflater;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WidgetApiTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private WidgetContext context(boolean expanded,String transport) {
        var id=UUID.randomUUID();
        return new WidgetContext(id,UUID.randomUUID(),"REMINDER",UUID.randomUUID(),UUID.randomUUID(),null,123L,456L,
                transport,OffsetDateTime.now().plusDays(7),2,0,expanded,
                new WidgetState("reminder","<b>Задача & данные</b>",List.of(List.of("Срок","Сегодня, 14:20"))).identity(id,2));
    }
    @Test void sendCarriesPrivateStateWithoutDuplicatingTheTitleBelowTheWidget() {
        var builder=RestClient.builder().baseUrl("https://example.test/botTOKEN");
        var server=MockRestServiceServer.bindTo(builder).build();
        var api=new WidgetApi(builder.build(),mapper);
        var c=context(false,"WIDGET");
        server.expect(requestTo("https://example.test/botTOKEN/sendWidget"))
                .andExpect(jsonPath("$.allow_queries").value(true)).andExpect(jsonPath("$.public_data").doesNotExist())
                .andExpect(jsonPath("$.caption").value(""))
                .andExpect(jsonPath("$.parse_mode").doesNotExist())
                .andExpect(jsonPath("$.reply_markup.inline_keyboard[0][0].text").value("Подробнее"))
                .andRespond(withSuccess("{\"ok\":true,\"result\":{\"message_id\":567}}",MediaType.APPLICATION_JSON));
        assertThat(api.send(c,"https://app.test")).isEqualTo(567);
        server.verify();
    }
    @Test void syncUpdatesDataAndCaptionOnSameMessageAndIgnoresNoChange() {
        var builder=RestClient.builder().baseUrl("https://example.test");
        var server=MockRestServiceServer.bindTo(builder).build();
        var api=new WidgetApi(builder.build(),mapper);var c=context(true,"WIDGET");
        server.expect(requestTo("https://example.test/editMessageWidgetData")).andExpect(jsonPath("$.message_id").value(456))
                .andRespond(withBadRequest().body("{\"description\":\"Bad Request: message is not modified\"}"));
        server.expect(requestTo("https://example.test/editMessageCaption")).andExpect(jsonPath("$.message_id").value(456))
                .andExpect(jsonPath("$.caption").value(WidgetTemplate.caption(c.state())))
                .andExpect(jsonPath("$.reply_markup.inline_keyboard[0][0].callback_data").value(c.state().key()+":done"))
                .andRespond(withSuccess("{\"ok\":true}",MediaType.APPLICATION_JSON));
        api.sync(c,"https://app.test");server.verify();
    }
    @Test void syncingAnUnexpandedWidgetClearsAnOldCaption() {
        var builder=RestClient.builder().baseUrl("https://example.test");
        var server=MockRestServiceServer.bindTo(builder).build();
        var api=new WidgetApi(builder.build(),mapper);var c=context(false,"WIDGET");
        server.expect(requestTo("https://example.test/editMessageWidgetData"))
                .andRespond(withSuccess("{\"ok\":true}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://example.test/editMessageCaption"))
                .andExpect(jsonPath("$.caption").value(""))
                .andRespond(withSuccess("{\"ok\":true}",MediaType.APPLICATION_JSON));
        api.sync(c,"https://app.test");server.verify();
    }
    @Test void answersFitTelegramBudgetAndTemplateIsCompressedWithinLimit() throws Exception {
        var c=context(false,"WIDGET");
        String answer="{\"ok\":true,\"state\":"+WidgetTemplate.encode(mapper,c.state())+"}";
        assertThat(answer.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1024);
        var bytes=WidgetTemplate.html().getBytes(StandardCharsets.UTF_8);
        var zip=new Deflater(9);zip.setInput(bytes);zip.finish();
        assertThat(zip.deflate(new byte[4097])).isLessThanOrEqualTo(4096);assertThat(zip.finished()).isTrue();zip.end();
        assertThatThrownBy(()->WidgetTemplate.encode(mapper,new WidgetState("reminder","я".repeat(1000),List.of())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(WidgetTemplate.clip("🐦".repeat(8),5)).isEqualTo("🐦🐦🐦🐦…");
    }
}
