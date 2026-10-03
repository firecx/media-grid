package io.mediagrid.support.events;

import java.util.ArrayList;
import java.util.List;

import io.mediagrid.common.events.Events;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * Объявление очередей службы-получателя по общим правилам шины (см. {@link Events}):
 * постоянная очередь {@code <служба>.<ключ>}, привязанная к ключу события, и очередь
 * {@code <очередь>.dlq} для сообщений, которые не удалось обработать.
 */
public final class EventQueues {

    private EventQueues() {
    }

    /** Имя очереди службы для события. */
    public static String queueName(String service, String routingKey) {
        return service + "." + routingKey;
    }

    /** Точки обмена и очереди службы для перечисленных ключей событий. */
    public static Declarables declare(String service, String... routingKeys) {
        TopicExchange events = new TopicExchange(Events.EXCHANGE);
        DirectExchange deadLetters = new DirectExchange(Events.DEAD_LETTER_EXCHANGE);
        List<Declarable> declarables = new ArrayList<>(List.of(events, deadLetters));
        for (String routingKey : routingKeys) {
            String name = queueName(service, routingKey);
            String dlq = name + ".dlq";
            Queue queue = QueueBuilder.durable(name)
                    .deadLetterExchange(Events.DEAD_LETTER_EXCHANGE)
                    .deadLetterRoutingKey(dlq)
                    .build();
            Queue deadLetterQueue = QueueBuilder.durable(dlq).build();
            declarables.add(queue);
            declarables.add(deadLetterQueue);
            declarables.add(BindingBuilder.bind(queue).to(events).with(routingKey));
            declarables.add(BindingBuilder.bind(deadLetterQueue).to(deadLetters).with(dlq));
        }
        return new Declarables(declarables);
    }

    /** Только точка обмена — для служб, которые события лишь публикуют. */
    public static Declarables publisherOnly() {
        return new Declarables(new TopicExchange(Events.EXCHANGE));
    }
}
