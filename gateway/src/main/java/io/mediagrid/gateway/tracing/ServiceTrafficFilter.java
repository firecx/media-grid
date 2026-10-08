package io.mediagrid.gateway.tracing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * Служебные запросы в трассы не пишутся: проверки готовности (/actuator/**) и обращения к регистру
 * служб (/eureka/**). Так же устроено в прикладных службах (service-support), но входящие запросы
 * шлюза — реактивные.
 */
@Component
public class ServiceTrafficFilter implements ObservationPredicate {

    @Override
    public boolean test(String name, Observation.Context context) {
        if (context instanceof ServerRequestObservationContext server && server.getCarrier() != null) {
            return !server.getCarrier().getPath().value().startsWith("/actuator");
        }
        if (context instanceof ClientRequestObservationContext client && client.getCarrier() != null) {
            return !client.getCarrier().getURI().getPath().startsWith("/eureka/");
        }
        return true;
    }
}
