package ru.taskflow.user.infrastructure.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.taskflow.shared.security.JwtService;
import ru.taskflow.shared.security.TelegramInitDataValidator;
import ru.taskflow.shared.security.TelegramLoginWidgetValidator;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.application.RefreshTokenService;
import ru.taskflow.user.infrastructure.web.dto.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final TelegramInitDataValidator initDataValidator;
    private final TelegramLoginWidgetValidator loginWidgetValidator;
    private final JwtService jwtService;
    private final UserService userService;
    private final RefreshTokenService refreshTokenService;

    @PostMapping("/telegram-miniapp")
    @ResponseStatus(HttpStatus.OK)
    public AuthResponse miniAppAuth(@Valid @RequestBody TelegramMiniAppAuthRequest request) {
        if (!initDataValidator.validate(request.initData())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid initData");
        }
        var user = parseMiniAppUser(request.initData());
        var dto = userService.findOrCreateByTelegram(
                user.id(), user.username(), user.firstName(), user.lastName());
        return issueTokens(dto.id(), dto.username());
    }

    @PostMapping("/telegram-login")
    @ResponseStatus(HttpStatus.OK)
    public AuthResponse loginWidgetAuth(@Valid @RequestBody TelegramLoginWidgetAuthRequest request) {
        if (!loginWidgetValidator.validate(request.fields())) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid login widget data");
        }
        Map<String, String> f = request.fields();
        long telegramId = Long.parseLong(f.get("id"));
        var dto = userService.findOrCreateByTelegram(
                telegramId, f.get("username"), f.get("first_name"), f.get("last_name"));
        return issueTokens(dto.id(), dto.username());
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.OK)
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        UUID userId = refreshTokenService.resolve(request.refreshToken())
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token"));
        refreshTokenService.revoke(request.refreshToken());
        var dto = userService.findById(userId);
        return issueTokens(dto.id(), dto.username());
    }

    @PostMapping("/dev-token")
    @ResponseStatus(HttpStatus.OK)
    public AuthResponse devToken(@RequestBody Map<String, String> body) {
        UUID userId = UUID.fromString(body.get("userId"));
        String username = body.get("username");
        return issueTokens(userId, username);
    }

    private AuthResponse issueTokens(UUID userId, String username) {
        String accessToken = jwtService.issueAccessToken(userId, username);
        String refreshToken = refreshTokenService.issue(userId);
        return new AuthResponse(accessToken, refreshToken);
    }

    private record TelegramUser(long id, String username, String firstName, String lastName) {}

    private TelegramUser parseMiniAppUser(String initData) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : initData.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) continue;
            params.put(pair.substring(0, idx), URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
        }
        String userJson = params.getOrDefault("user", "{}");
        long id = extractJsonLong(userJson, "id");
        String username = extractJsonString(userJson, "username");
        String firstName = extractJsonString(userJson, "first_name");
        String lastName = extractJsonString(userJson, "last_name");
        return new TelegramUser(id, username, firstName, lastName);
    }

    private long extractJsonLong(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return 0;
        int start = idx + pattern.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try { return Long.parseLong(json.substring(start, end)); } catch (NumberFormatException e) { return 0; }
    }

    private String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int start = idx + pattern.length();
        int end = json.indexOf("\"", start);
        return end < 0 ? null : json.substring(start, end);
    }
}