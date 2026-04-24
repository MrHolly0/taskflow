package ru.taskflow.nlp.api;

public interface NlpGatewayService {
    NlpParseResult parseText(String text, String userTimezone);

    NlpParseResult parseVoice(byte[] audioBytes, String userTimezone);
}
