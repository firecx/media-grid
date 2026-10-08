package io.mediagrid.auth.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * Шлюз запрашивает список отозванных токенов каждые несколько секунд — в трассы эти запросы не пишутся,
 * как и проверки готовности (ServiceTrafficFilter из service-support).
 */
@Component
class RevocationPollFilter implements ObservationPredicate {

    @Override
    public boolean test(String name, Observation.Context context) {
        return !(context instanceof ServerRequestObservationContext server && server.getCarrier() != null
                && "/internal/auth/revocations".equals(server.getCarrier().getRequestURI()));
    }
}
