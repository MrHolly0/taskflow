package ru.taskflow.shared.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "test_secret_key_must_be_at_least_256_bits_long_for_hs256";
    private static final long TTL_SECONDS = 900L; // 15 min

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, TTL_SECONDS);
    }

    @Test
    void issuedToken_extractsCorrectUserId() {
        UUID userId = UUID.randomUUID();

        String token = jwtService.issueAccessToken(userId, "testuser");
        var extracted = jwtService.extractUserId(token);

        assertThat(extracted).isPresent().contains(userId);
    }

    @Test
    void expiredToken_returnsEmpty() throws InterruptedException {
        JwtService shortLived = new JwtService(SECRET, 1L);
        UUID userId = UUID.randomUUID();

        String token = shortLived.issueAccessToken(userId, "testuser");
        Thread.sleep(1500);

        assertThat(shortLived.extractUserId(token)).isEmpty();
    }

    @Test
    void tamperedToken_returnsEmpty() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.issueAccessToken(userId, "testuser");
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        assertThat(jwtService.extractUserId(tampered)).isEmpty();
    }

    @Test
    void randomString_returnsEmpty() {
        assertThat(jwtService.extractUserId("not.a.token")).isEmpty();
        assertThat(jwtService.extractUserId("")).isEmpty();
        assertThat(jwtService.extractUserId(null)).isEmpty();
    }

    @Test
    void differentTokensForDifferentUsers() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();

        String token1 = jwtService.issueAccessToken(user1, "user1");
        String token2 = jwtService.issueAccessToken(user2, "user2");

        assertThat(token1).isNotEqualTo(token2);
        assertThat(jwtService.extractUserId(token1)).contains(user1);
        assertThat(jwtService.extractUserId(token2)).contains(user2);
    }
}
