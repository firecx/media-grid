package io.mediagrid.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Параметры проверки токенов доступа (раздел mediagrid.security в config/application.yml). */
@ConfigurationProperties("mediagrid.security")
public record SecurityProperties(String issuer, String jwkSetUri) {
}
