package ru.taskflow.shared.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

public class TelegramLoginWidgetValidator {

    private final String botToken;
    private final long maxAgeSeconds;

    public TelegramLoginWidgetValidator(String botToken, long maxAgeSeconds) {
        this.botToken = botToken;
        this.maxAgeSeconds = maxAgeSeconds;
    }

    public boolean validate(Map<String, String> fields) {
        if (fields == null || fields.isEmpty()) return false;

        Map<String, String> data = new TreeMap<>(fields);
        String hash = data.remove("hash");
        if (hash == null) return false;

        String authDateStr = data.get("auth_date");
        if (authDateStr == null) return false;
        try {
            long authDate = Long.parseLong(authDateStr);
            if (System.currentTimeMillis() / 1000 - authDate > maxAgeSeconds) return false;
        } catch (NumberFormatException e) {
            return false;
        }

        String dataCheckString = data.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        try {
            byte[] secretKey = MessageDigest.getInstance("SHA-256")
                    .digest(botToken.getBytes(StandardCharsets.UTF_8));

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            String expected = HexFormat.of().formatHex(
                    mac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8)));

            return expected.equals(hash);
        } catch (Exception e) {
            return false;
        }
    }
}