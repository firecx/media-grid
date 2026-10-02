package io.mediagrid.auth.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Настройки службы авторизации (раздел mediagrid.auth в config/auth-service.yml). */
@ConfigurationProperties("mediagrid.auth")
public record AuthProperties(
        @DefaultValue("mediagrid-auth") String issuer,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("30d") Duration refreshTokenTtl,
        @DefaultValue Cookie cookie,
        @DefaultValue BootstrapAdmin bootstrapAdmin) {

    /** Куки с обновляемым токеном. secure=false допустимо только для отладки без защищённого канала. */
    public record Cookie(
            @DefaultValue("mediagrid_refresh") String name,
            @DefaultValue("true") boolean secure) {
    }

    /** Первый администратор: создаётся при запуске, если администраторов в базе нет. */
    public record BootstrapAdmin(String email, String password) {
    }
}
