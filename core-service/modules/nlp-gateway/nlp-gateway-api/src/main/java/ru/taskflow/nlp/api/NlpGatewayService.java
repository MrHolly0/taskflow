package ru.taskflow.nlp.api;

import java.util.List;

public interface NlpGatewayService {
    NlpParseResult parseText(String text, String userTimezone, List<String> existingGroups);

    NlpParseResult parseVoice(byte[] audioBytes, String userTimezone, List<String> existingGroups);
}
