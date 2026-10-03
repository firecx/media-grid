package io.mediagrid.gateway.security;

import io.mediagrid.gateway.error.ApiErrorWriter;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Первичная проверка токена доступа до передачи запроса службе (ТЗ, п. 4.2.5).
 * Службы проверяют токен ещё раз сами: шлюз — не единственная защита.
 */
@Configuration
public class SecurityConfig {

    /** Утверждение со списком ролей в токене службы авторизации. */
    private static final String ROLES_CLAIM = "roles";

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ApiErrorWriter errors) {
        ServerAuthenticationEntryPoint unauthorized = (exchange, e) -> errors.write(exchange,
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Требуется вход в систему");
        ServerAccessDeniedHandler forbidden = (exchange, e) -> errors.write(exchange,
                HttpStatus.FORBIDDEN, "FORBIDDEN", "Недостаточно прав");
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                // Состояние на шлюзе не хранится: каждый запрос несёт свой токен
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(ex -> ex
                        .pathMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh", "/api/auth/logout")
                        .permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/auth/jwks").permitAll()
                        // Файл по ссылке с ограниченным сроком: вместо токена служба хранения проверяет подпись.
                        // Тег video не умеет передавать заголовок Authorization
                        .pathMatchers(HttpMethod.GET, "/api/files/*/content").permitAll()
                        .pathMatchers(HttpMethod.HEAD, "/api/files/*/content").permitAll()
                        // Служебные точки шлюза доступны только на порту 8081, который наружу не публикуется
                        .pathMatchers("/actuator/**").permitAll()
                        // Административные функции всех служб — по единому пути /api/<раздел>/admin/**
                        .pathMatchers("/api/*/admin/**").hasRole("ADMIN")
                        // Токен службы (роль SERVICE) снаружи бесполезен: пути /api/** — только для людей
                        .pathMatchers("/api/**").hasAnyRole("USER", "ADMIN")
                        .anyExchange().denyAll())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .build();
    }

    /**
     * Открытые ключи берутся у службы авторизации через балансировщик и хранятся в памяти;
     * при встрече токена с незнакомым ключом список перечитывается.
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(SecurityProperties properties,
                                  ReactorLoadBalancerExchangeFilterFunction loadBalancer) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .webClient(WebClient.builder().filter(loadBalancer).build())
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    private static ReactiveJwtAuthenticationConverterAdapter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
