package io.mediagrid.processing.storage;

import java.net.http.HttpClient;
import java.time.Duration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.mediagrid.processing.job.JobFailure;
import org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class ClientSetup {

    /**
     * Основа клиентов к другим службам: адрес вида http://storage-service разрешается через регистр.
     * defaultCandidate = false: не вытесняет стандартный RestClient.Builder, которым пользуется
     * клиент регистра (Eureka), — иначе служба не смогла бы зарегистрироваться.
     */
    @Bean(defaultCandidate = false)
    @LoadBalanced
    RestClient.Builder serviceRestClientBuilder(RestClientBuilderConfigurer configurer) {
        // Настройки Spring Boot, в том числе трассировка: номер трассы уходит в заголовке traceparent
        return configurer.configure(RestClient.builder());
    }

    /**
     * Размыкатель цепи к службе хранения (через неё же идёт и получение токена). Если из последних 10
     * обращений (не меньше 3) половина — временные сбои, цепь размыкается на 30 секунд: исполнители
     * не берут новые задачи и не тратят на них попытки.
     */
    @Bean
    CircuitBreaker storageBreaker() {
        return CircuitBreaker.of("storage-service", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(3)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordExceptions(JobFailure.Transient.class)
                .ignoreExceptions(JobFailure.Gone.class, JobFailure.Permanent.class)
                .build());
    }

    static JdkClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
