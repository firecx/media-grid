package io.mediagrid.support;

import io.mediagrid.support.security.SecurityProperties;
import io.mediagrid.support.web.ApiExceptionHandler;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

/**
 * Подключается к службе сама (META-INF/spring/...AutoConfiguration.imports).
 * Служба может заменить любой из компонентов своим, например служба авторизации — проверку токенов.
 */
@AutoConfiguration
@EnableConfigurationProperties(SecurityProperties.class)
public class ServiceSupportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ApiExceptionHandler apiExceptionHandler() {
        return new ApiExceptionHandler();
    }

    /**
     * Проверка токенов по открытым ключам службы авторизации. Ключи запрашиваются при первой
     * необходимости через балансировщик и хранятся в памяти; незнакомый ключ — повод перечитать.
     */
    @Bean
    @ConditionalOnMissingBean
    JwtDecoder jwtDecoder(SecurityProperties properties, @LoadBalanced RestTemplate jwksRestTemplate) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .restOperations(jwksRestTemplate)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    /** Только для запроса ключей; не вытесняет стандартные клиенты, которыми пользуется, например, Eureka. */
    @Bean(defaultCandidate = false)
    @LoadBalanced
    RestTemplate jwksRestTemplate() {
        return new RestTemplate();
    }

    /** Для служб, работающих с шиной сообщений. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RabbitTemplate.class)
    static class Messaging {

        /** События — JSON; разбираются только классы событий системы. */
        @Bean
        @ConditionalOnMissingBean
        MessageConverter messageConverter() {
            return new JacksonJsonMessageConverter("io.mediagrid.common.events");
        }
    }
}
