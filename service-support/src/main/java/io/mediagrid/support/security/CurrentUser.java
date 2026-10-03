package io.mediagrid.support.security;

import java.util.List;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/** Пользователь, от имени которого выполняется запрос, — из проверенного токена доступа. */
public record CurrentUser(UUID id, boolean admin) {

    public static CurrentUser of(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(ResourceServerSecurity.ROLES_CLAIM);
        return new CurrentUser(UUID.fromString(jwt.getSubject()), roles != null && roles.contains("ADMIN"));
    }
}
