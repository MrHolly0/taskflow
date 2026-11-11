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
                .andExpect(jsonPath("$.reply_markup.inline_keyboard[0][0].text").value("Показать текст"))
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
    @Test void realDescriptionIsLabeledAndDuplicateOrMissingDescriptionIsOmitted() throws Exception {
        var base=context(false,"WIDGET").state();
        var described=WidgetTemplate.withDescription(mapper,base,base.title(),"Подготовить материалы и проверить аудиторию");
        assertThat(WidgetTemplate.caption(described)).contains("Задача: "+base.title(),"Описание: Подготовить материалы");
        assertThat(described.identity(UUID.randomUUID(),9).finish("Готово","Завершено").description()).isEqualTo(described.description());
        assertThat(WidgetTemplate.withDescription(mapper,base,"Подготовить пару","  подготовить   пару. ").description()).isNull();
        assertThat(WidgetTemplate.withDescription(mapper,base,base.title(),null).description()).isNull();
        assertThat(WidgetTemplate.caption(base)).doesNotContain("Описание:");
        String old=WidgetTemplate.encode(mapper,base);
        assertThat(mapper.readValue(old,WidgetState.class).description()).isNull();
    }
    @Test void descriptionsWithEmojiAndEscapedCharactersFitTheRealMessageBudget() {
        var base=context(false,"WIDGET").state();
        for(String value:List.of("🐦".repeat(1000),"\"\n".repeat(1000),"Описание ".repeat(1000))) {
            var state=WidgetTemplate.withDescription(mapper,base,base.title(),value).identity(UUID.randomUUID(),999999999);
            assertThat(state.description()).isNotBlank();
            assertThat(WidgetTemplate.encode(mapper,state).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(960);
        }
    }
    @Test void actionFeedbackCanShrinkDescriptionWithoutLosingTheActionResult() throws Exception {
        var base=context(false,"WIDGET").state().checkins(0,50,UUID.randomUUID().toString(),null);
        var described=WidgetTemplate.withDescription(mapper,base,base.title(),"🐦".repeat(1000));
        var finished=described.feedback("«"+"Длинное название ".repeat(5)+"» — готово!").finish("Действие завершено","Ответ сохранён");
        var saved=mapper.readValue(WidgetTemplate.encode(mapper,finished),WidgetState.class);
        assertThat(saved.heading()).isEqualTo("Действие завершено");assertThat(saved.status()).isEqualTo("Ответ сохранён");
        assertThat(saved.warning()).isEqualTo(finished.warning());assertThat(saved.key()).isEqualTo(base.key());
        assertThat(WidgetTemplate.encode(mapper,saved).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(960);
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
