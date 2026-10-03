package io.mediagrid.support.security;

import io.mediagrid.support.web.ErrorResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Общая настройка защиты прикладной службы: без состояния, проверка токена доступа в заголовке
 * Authorization, роли из утверждения roles, ошибки в формате ApiError. Правила доступа по путям
 * служба задаёт сама после вызова {@link #apply}.
 */
public final class ResourceServerSecurity {

    /** Утверждение со списком ролей в токене службы авторизации. */
    public static final String ROLES_CLAIM = "roles";

    /** Роли людей. Пользовательские пути /api/** — только для них: у токена службы нет пользователя. */
    public static final String[] USER_ROLES = {"USER", "ADMIN"};

    /**
     * Роль служб (токен выдаёт служба авторизации по секрету службы). Открывает только внутренние
     * пути /internal/**, которые шлюз наружу не пропускает.
     */
    public static final String SERVICE_ROLE = "SERVICE";

    private static final Logger log = LoggerFactory.getLogger(ResourceServerSecurity.class);

    private ResourceServerSecurity() {
    }

    public static HttpSecurity apply(HttpSecurity http, JsonMapper json) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, e) -> ErrorResponses.write(response, json,
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Требуется вход в систему");
        AccessDeniedHandler forbidden = (request, response, e) -> ErrorResponses.write(response, json,
                HttpStatus.FORBIDDEN, "FORBIDDEN", "Недостаточно прав");
        return http
                // Состояние на сервере не хранится, токен идёт в заголовке: подделка запросов с чужого сайта не грозит
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden)
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                filter.setAuthenticationFailureHandler(tokenCheckFailure(unauthorized, json));
                                return filter;
                            }
                        }))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden));
    }

    /**
     * Неверный токен — 401. Но если токен нельзя проверить, потому что служба авторизации недоступна
     * (ключи ещё не получены), это 503: клиент может повторить запрос позже.
     */
    private static AuthenticationFailureHandler tokenCheckFailure(AuthenticationEntryPoint unauthorized,
                                                                  JsonMapper json) {
        return (request, response, e) -> {
            if (e instanceof AuthenticationServiceException) {
                log.warn("Не удалось проверить токен: {}", e.getMessage());
                ErrorResponses.write(response, json, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                        "Проверка входа временно недоступна, повторите запрос позже");
            } else {
                unauthorized.commence(request, response, e);
            }
        };
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
