package io.mediagrid.storage.config;

import io.mediagrid.support.security.ResourceServerSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * Все запросы — с токеном доступа, кроме выдачи файла по подписанной ссылке: там вместо токена
 * проверяются подпись и срок действия ссылки (FileService.fileByLink).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper json) throws Exception {
        return ResourceServerSecurity.apply(http, json)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/files/*/content").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/api/files/*/content").permitAll()
                        .requestMatchers("/api/files/**").hasAnyRole(ResourceServerSecurity.USER_ROLES)
                        // Обмен файлами со службой обработки — только по токену службы
                        .requestMatchers("/internal/**").hasRole(ResourceServerSecurity.SERVICE_ROLE)
                        .anyRequest().denyAll())
                .build();
    }
}
