package io.mediagrid.support.web;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Служебные запросы в трассы не пишутся: проверки готовности (/actuator/**, Docker обращается к ним
 * каждые несколько секунд) и обращения к регистру служб (/eureka/**, отметки каждые 30 секунд).
 * Иначе запросы пользователей теряются среди сотен служебных трасс.
 */
public class ServiceTrafficFilter implements ObservationPredicate {

    @Override
    public boolean test(String name, Observation.Context context) {
        if (context instanceof ServerRequestObservationContext server && server.getCarrier() != null) {
            return !server.getCarrier().getRequestURI().startsWith("/actuator");
        }
        if (context instanceof ClientRequestObservationContext client && client.getCarrier() != null) {
            return !client.getCarrier().getURI().getPath().startsWith("/eureka/");
        }
        return true;
    }
}
