package ru.taskflow.shared.widgets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.time.Duration;
import java.util.Map;

public final class WidgetApi {
    private final RestClient client;
    private final ObjectMapper mapper;

    public WidgetApi(String baseUrl, String token, ObjectMapper mapper) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = RestClient.builder().requestFactory(factory).baseUrl(baseUrl + "/bot" + token).build();
        this.mapper = mapper;
    }
    public WidgetApi(RestClient client, ObjectMapper mapper) { this.client = client; this.mapper = mapper; }

    public long send(WidgetContext context, String appUrl) {
        var body = Map.of("chat_id",context.chatId(),"html",WidgetTemplate.html(),
                "chat_data",WidgetTemplate.encode(mapper,context.state()),"allow_queries",true,
                "caption",WidgetTemplate.caption(context.state()),"reply_markup",WidgetKeyboard.of(context,appUrl));
        return post("sendWidget",body).path("result").path("message_id").asLong();
    }
    public long sendText(WidgetContext context, String appUrl) {
        return post("sendMessage",Map.of("chat_id",context.chatId(),"text",WidgetTemplate.caption(context.state()),
                "reply_markup",WidgetKeyboard.of(context,appUrl))).path("result").path("message_id").asLong();
    }
    public void sync(WidgetContext context, String appUrl) {
        var keyboard = WidgetKeyboard.of(context,appUrl);
        if ("TEXT".equals(context.transport())) {
            edit("editMessageText",Map.of("chat_id",context.chatId(),"message_id",context.messageId(),
                    "text",WidgetTemplate.caption(context.state()),"reply_markup",keyboard));
        } else {
            edit("editMessageWidgetData",Map.of("chat_id",context.chatId(),"message_id",context.messageId(),
                    "chat_data",WidgetTemplate.encode(mapper,context.state())));
            edit("editMessageCaption",Map.of("chat_id",context.chatId(),"message_id",context.messageId(),
                    "caption",WidgetTemplate.caption(context.state()),"reply_markup",keyboard));
        }
    }
    public void answer(String queryId, WidgetState state) {
        String data = "{\"ok\":true,\"state\":" + WidgetTemplate.encode(mapper,state) + "}";
        post("answerWidgetQuery",Map.of("widget_query_id",queryId,"data",data));
    }
    public void answerError(String queryId, String message) {
        post("answerWidgetQuery",Map.of("widget_query_id",queryId,"data",mapper.valueToTree(Map.of("ok",false,"message",message)).toString()));
    }
    private void edit(String method, Object body) {
        try { post(method,body); }
        catch (RestClientResponseException e) {
            if (!e.getResponseBodyAsString().contains("message is not modified")) throw e;
        }
    }
    private JsonNode post(String method, Object body) {
        JsonNode result = client.post().uri("/"+method).body(body).retrieve().body(JsonNode.class);
        if (result == null || !result.path("ok").asBoolean()) throw new IllegalStateException("TELEGRAM_INVALID_RESPONSE");
        if ((method.equals("sendWidget") || method.equals("sendMessage")) && result.path("result").path("message_id").asLong() <= 0)
            throw new IllegalStateException("TELEGRAM_MISSING_MESSAGE_ID");
        return result;
    }
    public static boolean unsupported(RestClientResponseException e) {
        String body = e.getResponseBodyAsString().toLowerCase(java.util.Locale.ROOT);
        return e.getStatusCode().value()==404 || (e.getStatusCode().value()==400 &&
                (body.contains("method not found") || body.contains("widgets are not supported")));
    }
}
