package io.mediagrid.processing.config;

import io.mediagrid.support.security.ResourceServerSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ход обработки видят владелец файла и администратор; управление очередью — только администратор.
 * Общая часть (проверка токенов, ошибки) — в service-support.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper json) throws Exception {
        return ResourceServerSecurity.apply(http, json)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .requestMatchers("/api/processing/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/processing/**").hasAnyRole(ResourceServerSecurity.USER_ROLES)
                        .anyRequest().denyAll())
                .build();
    }
}
