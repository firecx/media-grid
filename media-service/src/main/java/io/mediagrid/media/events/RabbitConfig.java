package io.mediagrid.media.events;

import io.mediagrid.common.events.Events;
import io.mediagrid.support.events.EventQueues;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Очереди службы медиаданных; общее устройство шины описано в {@link Events}. */
@Configuration
public class RabbitConfig {

    static final String SERVICE = "media-service";
    public static final String FILE_UPLOADED_QUEUE = SERVICE + "." + Events.FILE_UPLOADED;
    public static final String PROCESSING_COMPLETED_QUEUE = SERVICE + "." + Events.PROCESSING_COMPLETED;

    @Bean
    Declarables mediaServiceQueues() {
        return EventQueues.declare(SERVICE, Events.FILE_UPLOADED, Events.PROCESSING_COMPLETED);
    }
}
