package io.mediagrid.gateway.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Размыкание и замыкание цепи к службе — в журнал: по ним видно, когда и надолго ли служба
 * становилась недоступной. Отдельные отклонённые запросы в журнал не пишутся.
 */
@Configuration(proxyBeanMethods = false)
public class BreakerEvents {

    private static final Logger log = LoggerFactory.getLogger(BreakerEvents.class);

    @Bean
    Customizer<ReactiveResilience4JCircuitBreakerFactory> breakerTransitionLogging() {
        // Размыкатели создаются при первом запросе по маршруту — подписка на каждый новый
        return factory -> factory.getCircuitBreakerRegistry().getEventPublisher()
                .onEntryAdded(added -> added.getAddedEntry().getEventPublisher()
                        .onStateTransition(BreakerEvents::logTransition));
    }

    private static void logTransition(CircuitBreakerOnStateTransitionEvent event) {
        CircuitBreaker.State to = event.getStateTransition().getToState();
        switch (to) {
            case OPEN -> log.warn("Цепь к маршруту {} разомкнута: служба недоступна, запросы получают 503",
                    event.getCircuitBreakerName());
            case HALF_OPEN -> log.info("Цепь к маршруту {}: пробные запросы", event.getCircuitBreakerName());
            case CLOSED -> log.info("Цепь к маршруту {} замкнута: служба снова отвечает",
                    event.getCircuitBreakerName());
            default -> log.info("Цепь к маршруту {}: {}", event.getCircuitBreakerName(), to);
        }
    }
}
