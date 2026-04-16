package ru.taskflow.shared.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public class TelegramInitDataValidator {

    private final String botToken;
    private final long maxAgeSeconds;

    public TelegramInitDataValidator(String botToken, long maxAgeSeconds) {
        this.botToken = botToken;
        this.maxAgeSeconds = maxAgeSeconds;
    }

    public boolean validate(String initData) {
        if (initData == null || initData.isBlank()) return false;

        Map<String, String> params = parseQueryString(initData);
        String hash = params.remove("hash");
        if (hash == null) return false;

        String authDateStr = params.get("auth_date");
        if (authDateStr == null) return false;
        try {
            long authDate = Long.parseLong(authDateStr);
            if (System.currentTimeMillis() / 1000 - authDate > maxAgeSeconds) return false;
        } catch (NumberFormatException e) {
            return false;
        }

        String dataCheckString = new TreeMap<>(params).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        try {
            Mac secretMac = Mac.getInstance("HmacSHA256");
            secretMac.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] secretKey = secretMac.doFinal(botToken.getBytes(StandardCharsets.UTF_8));

            Mac hashMac = Mac.getInstance("HmacSHA256");
            hashMac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            String expected = HexFormat.of().formatHex(
                    hashMac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8)));

            return expected.equals(hash);
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, String> parseQueryString(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) continue;
            String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }
}