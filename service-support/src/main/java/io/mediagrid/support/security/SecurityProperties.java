package io.mediagrid.support.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Параметры проверки токенов доступа (раздел mediagrid.security в config/application.yml).
 *
 * @param issuer    издатель токенов — служба авторизации
 * @param jwkSetUri адрес открытых ключей; вместо хоста — имя службы в регистре
 */
@ConfigurationProperties("mediagrid.security")
public record SecurityProperties(String issuer, String jwkSetUri) {
}
