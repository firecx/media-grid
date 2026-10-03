package io.mediagrid.media.config;

import io.mediagrid.support.security.ResourceServerSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * Служба сама проверяет каждый токен, даже если запрос уже прошёл через шлюз.
 * Общая часть (проверка токенов, ошибки) — в service-support; здесь только правила по путям.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper json) throws Exception {
        return ResourceServerSecurity.apply(http, json)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .requestMatchers("/api/media/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/media/**").hasAnyRole(ResourceServerSecurity.USER_ROLES)
                        .anyRequest().denyAll())
                .build();
    }
}
