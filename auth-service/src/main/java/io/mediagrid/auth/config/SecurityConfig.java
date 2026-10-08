package io.mediagrid.auth.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import io.mediagrid.auth.key.SigningKeyService;
import io.mediagrid.support.security.ResourceServerSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * Общая часть защиты (проверка токенов, ошибки) — в service-support. Отличие службы авторизации:
 * токены она выпускает сама и проверяет своими ключами, без запроса к себе же по сети.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper json) throws Exception {
        // Куки обновления — SameSite=Strict, поэтому и вход с обновлением не страдают от подделки запросов
        return ResourceServerSecurity.apply(http, json)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh", "/api/auth/logout")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/jwks").permitAll()
                        // Токен службы: имя и секрет проверяет ServiceTokenController
                        .requestMatchers(HttpMethod.POST, "/internal/auth/token").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/error").permitAll()
                        // Административные функции отделены от пользовательских (ТЗ, п. 4.1.8)
                        .requestMatchers("/api/auth/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/auth/**").hasAnyRole(ResourceServerSecurity.USER_ROLES)
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return createPasswordEncoder();
    }

    /**
     * Хеширование паролей — одно для службы и для восстановления пароля администратора (AdminPasswordReset).
     * Сейчас BCrypt; отпечаток хранит название алгоритма, поэтому его можно сменить без сброса паролей.
     */
    public static PasswordEncoder createPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder(SigningKeyService keys) {
        return new NimbusJwtEncoder(keys.jwkSource());
    }

    /** Своя проверка вместо общей из service-support: ключи берутся прямо из базы службы. */
    @Bean
    JwtDecoder jwtDecoder(SigningKeyService keys, AuthProperties properties) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys.jwkSource()));
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }
}
