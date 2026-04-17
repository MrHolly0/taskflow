package ru.taskflow.telegram.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
public class TelegramApiClient {

    private final String botToken;
    private final RestClient apiClient;
    private final RestClient fileClient;

    public TelegramApiClient(String botToken) {
        this.botToken = botToken;
        this.apiClient = RestClient.builder()
                .baseUrl("https://api.telegram.org/bot" + botToken)
                .build();
        this.fileClient = RestClient.builder()
                .baseUrl("https://api.telegram.org/file/bot" + botToken)
                .build();
    }

    public void sendMessage(long chatId, String text, String parseMode) {
        apiClient.post()
                .uri("/sendMessage")
                .body(Map.of("chat_id", chatId, "text", text, "parse_mode", parseMode))
                .retrieve()
                .toBodilessEntity();
    }

    public void sendMessageWithKeyboard(long chatId, String text, String parseMode, Object replyMarkup) {
        apiClient.post()
                .uri("/sendMessage")
                .body(Map.of("chat_id", chatId, "text", text,
                        "parse_mode", parseMode, "reply_markup", replyMarkup))
                .retrieve()
                .toBodilessEntity();
    }

    public void answerCallbackQuery(String callbackQueryId, String text) {
        apiClient.post()
                .uri("/answerCallbackQuery")
                .body(Map.of("callback_query_id", callbackQueryId, "text", text))
                .retrieve()
                .toBodilessEntity();
    }

    public byte[] downloadVoice(String fileId) {
        var fileInfo = apiClient.get()
                .uri("/getFile?file_id={id}", fileId)
                .retrieve()
                .body(GetFileResponse.class);

        if (fileInfo == null || fileInfo.result() == null) {
            throw new IllegalStateException("Failed to get file info for fileId=" + fileId);
        }

        return fileClient.get()
                .uri("/{path}", fileInfo.result().filePath())
                .retrieve()
                .body(byte[].class);
    }

    record GetFileResponse(boolean ok, @JsonProperty("result") FileInfo result) {}

    record FileInfo(@JsonProperty("file_id") String fileId,
                    @JsonProperty("file_path") String filePath) {}
}