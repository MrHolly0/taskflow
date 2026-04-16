package ru.taskflow.shared.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramLoginWidgetValidatorTest {

    private static final String BOT_TOKEN = "1234567890:AAHtest_token_for_unit_testing_only";
    private static final long MAX_AGE = 86400L;

    private TelegramLoginWidgetValidator validator;

    @BeforeEach
    void setUp() {
        validator = new TelegramLoginWidgetValidator(BOT_TOKEN, MAX_AGE);
    }

    @Test
    void validFields_returnsTrue() throws Exception {
        long authDate = System.currentTimeMillis() / 1000;
        var fields = new TreeMap<String, String>();
        fields.put("id", "123456789");
        fields.put("first_name", "Test");
        fields.put("auth_date", String.valueOf(authDate));

        String hash = computeWidgetHash(fields, BOT_TOKEN);
        fields.put("hash", hash);

        assertThat(validator.validate(fields)).isTrue();
    }

    @Test
    void tamperedField_returnsFalse() throws Exception {
        long authDate = System.currentTimeMillis() / 1000;
        var fields = new TreeMap<String, String>();
        fields.put("id", "123456789");
        fields.put("auth_date", String.valueOf(authDate));

        String hash = computeWidgetHash(fields, BOT_TOKEN);

        Map<String, String> tampered = new TreeMap<>(fields);
        tampered.put("id", "999999999");
        tampered.put("hash", hash);

        assertThat(validator.validate(tampered)).isFalse();
    }

    @Test
    void expiredAuthDate_returnsFalse() throws Exception {
        long expired = (System.currentTimeMillis() / 1000) - MAX_AGE - 1;
        var fields = new TreeMap<String, String>();
        fields.put("id", "123456789");
        fields.put("auth_date", String.valueOf(expired));

        String hash = computeWidgetHash(fields, BOT_TOKEN);
        fields.put("hash", hash);

        assertThat(validator.validate(fields)).isFalse();
    }

    @Test
    void missingHash_returnsFalse() {
        assertThat(validator.validate(Map.of("id", "123", "auth_date", "1234567890"))).isFalse();
    }

    private static String computeWidgetHash(TreeMap<String, String> fields, String botToken) throws Exception {
        String dataCheckString = fields.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        // Widget uses SHA-256(bot_token) as key, NOT HMAC
        byte[] secretKey = MessageDigest.getInstance("SHA-256")
                .digest(botToken.getBytes(StandardCharsets.UTF_8));

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8)));
    }
}
