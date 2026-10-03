package io.mediagrid.media.events;

import java.util.ArrayList;
import java.util.List;

import io.mediagrid.common.events.Events;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Очереди службы медиаданных; общее устройство шины описано в {@link Events}. */
@Configuration
public class RabbitConfig {

    public static final String FILE_UPLOADED_QUEUE = "media-service." + Events.FILE_UPLOADED;
    public static final String PROCESSING_COMPLETED_QUEUE = "media-service." + Events.PROCESSING_COMPLETED;

    @Bean
    MessageConverter messageConverter() {
        // Разбираются только классы событий системы
        return new JacksonJsonMessageConverter("io.mediagrid.common.events");
    }

    @Bean
    Declarables mediaServiceTopology() {
        TopicExchange events = new TopicExchange(Events.EXCHANGE);
        DirectExchange deadLetters = new DirectExchange(Events.DEAD_LETTER_EXCHANGE);
        List<Declarable> declarables = new ArrayList<>(List.of(events, deadLetters));
        declarables.addAll(queueWithDeadLetters(FILE_UPLOADED_QUEUE, events, Events.FILE_UPLOADED, deadLetters));
        declarables.addAll(queueWithDeadLetters(PROCESSING_COMPLETED_QUEUE, events, Events.PROCESSING_COMPLETED,
                deadLetters));
        return new Declarables(declarables);
    }

    /** Постоянная очередь, привязанная к ключу события, и очередь для сообщений, которые не удалось обработать. */
    static List<Declarable> queueWithDeadLetters(String name, TopicExchange events, String routingKey,
                                                 DirectExchange deadLetters) {
        String dlq = name + ".dlq";
        Queue queue = QueueBuilder.durable(name)
                .deadLetterExchange(Events.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(dlq)
                .build();
        Queue deadLetterQueue = QueueBuilder.durable(dlq).build();
        Binding binding = BindingBuilder.bind(queue).to(events).with(routingKey);
        Binding deadLetterBinding = BindingBuilder.bind(deadLetterQueue).to(deadLetters).with(dlq);
        return List.of(queue, deadLetterQueue, binding, deadLetterBinding);
    }
}
