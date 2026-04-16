package ru.taskflow.shared.security;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

public class JwtService {

    private final SecretKey secretKey;
    private final long accessTokenTtlSeconds;

    public JwtService(String secret, long accessTokenTtlSeconds) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    public String issueAccessToken(UUID userId, String telegramUsername) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenTtlSeconds * 1000);
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", telegramUsername)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    public Optional<UUID> extractUserId(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        try {
            String subject = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
            return Optional.of(UUID.fromString(subject));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}