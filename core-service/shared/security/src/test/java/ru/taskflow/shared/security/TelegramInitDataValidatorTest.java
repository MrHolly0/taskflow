package ru.taskflow.shared.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramInitDataValidatorTest {

    private static final String BOT_TOKEN = "1234567890:AAHtest_token_for_unit_testing_only";
    private static final long MAX_AGE = 86400L;

    private TelegramInitDataValidator validator;

    @BeforeEach
    void setUp() {
        validator = new TelegramInitDataValidator(BOT_TOKEN, MAX_AGE);
    }

    @Test
    void validInitData_returnsTrue() throws Exception {
        long authDate = System.currentTimeMillis() / 1000;
        var params = new TreeMap<String, String>();
        params.put("query_id", "AAHtest");
        params.put("user", "{\"id\":123456789,\"first_name\":\"Test\"}");
        params.put("auth_date", String.valueOf(authDate));

        String hash = computeInitDataHash(params, BOT_TOKEN);
        String initData = buildInitData(params, hash);

        assertThat(validator.validate(initData)).isTrue();
    }

    @Test
    void tamperedData_returnsFalse() throws Exception {
        long authDate = System.currentTimeMillis() / 1000;
        var params = new TreeMap<String, String>();
        params.put("query_id", "AAHtest");
        params.put("auth_date", String.valueOf(authDate));

        String hash = computeInitDataHash(params, BOT_TOKEN);
        // tamper the query_id after hash was computed
        String initData = "query_id=TAMPERED&auth_date=" + authDate + "&hash=" + hash;

        assertThat(validator.validate(initData)).isFalse();
    }

    @Test
    void wrongBotToken_returnsFalse() throws Exception {
        long authDate = System.currentTimeMillis() / 1000;
        var params = new TreeMap<String, String>();
        params.put("auth_date", String.valueOf(authDate));

        String hash = computeInitDataHash(params, "wrong_token");
        String initData = buildInitData(params, hash);

        assertThat(validator.validate(initData)).isFalse();
    }

    @Test
    void missingHash_returnsFalse() {
        assertThat(validator.validate("auth_date=1234567890&query_id=AAA")).isFalse();
    }

    @Test
    void expiredAuthDate_returnsFalse() throws Exception {
        long expired = (System.currentTimeMillis() / 1000) - MAX_AGE - 1;
        var params = new TreeMap<String, String>();
        params.put("auth_date", String.valueOf(expired));

        String hash = computeInitDataHash(params, BOT_TOKEN);
        String initData = buildInitData(params, hash);

        assertThat(validator.validate(initData)).isFalse();
    }

    @Test
    void emptyInitData_returnsFalse() {
        assertThat(validator.validate("")).isFalse();
        assertThat(validator.validate(null)).isFalse();
    }

    private static String computeInitDataHash(TreeMap<String, String> params, String botToken) throws Exception {
        String dataCheckString = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        Mac secretMac = Mac.getInstance("HmacSHA256");
        secretMac.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] secretKey = secretMac.doFinal(botToken.getBytes(StandardCharsets.UTF_8));

        Mac hashMac = Mac.getInstance("HmacSHA256");
        hashMac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
        return HexFormat.of().formatHex(hashMac.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8)));
    }

    private static String buildInitData(TreeMap<String, String> params, String hash) {
        StringBuilder sb = new StringBuilder();
        for (var e : params.entrySet()) {
            if (!sb.isEmpty()) sb.append("&");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        sb.append("&hash=").append(hash);
        return sb.toString();
    }
}
