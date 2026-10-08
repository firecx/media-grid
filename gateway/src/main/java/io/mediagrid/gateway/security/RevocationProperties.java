package io.mediagrid.gateway.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Получение списка отозванных токенов (раздел mediagrid.gateway.revocations в config/gateway.yml).
 * Шлюз входит в службу авторизации как служба: имя clientId и секрет GATEWAY_CLIENT_SECRET.
 */
@ConfigurationProperties("mediagrid.gateway.revocations")
public record RevocationProperties(
        @DefaultValue("gateway") String clientId,
        String clientSecret,
        // Задержка отзыва — не больше этого значения
        @DefaultValue("5s") Duration pollInterval,
        @DefaultValue("http://auth-service/internal/auth/token") String tokenUri,
        @DefaultValue("http://auth-service/internal/auth/revocations") String listUri) {
}
