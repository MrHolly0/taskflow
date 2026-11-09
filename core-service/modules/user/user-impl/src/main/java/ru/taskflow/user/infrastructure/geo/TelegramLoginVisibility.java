package ru.taskflow.user.infrastructure.geo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TelegramLoginVisibility {

    private final CountryResolver countryResolver;
    private final boolean alwaysVisible;

    public TelegramLoginVisibility(CountryResolver countryResolver,
                                   @Value("${app.telegram.login-always-visible:false}") boolean alwaysVisible) {
        this.countryResolver = countryResolver;
        this.alwaysVisible = alwaysVisible;
    }

    public boolean isVisible(String clientIp) {
        return alwaysVisible || countryResolver.resolveCountryIso(clientIp)
                .map(iso -> !"RU".equalsIgnoreCase(iso))
                .orElse(false);
    }
}
