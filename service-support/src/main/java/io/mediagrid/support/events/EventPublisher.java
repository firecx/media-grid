package io.mediagrid.support.events;

import io.mediagrid.common.events.Events;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Отправка события в шину с подтверждением брокера (spring.rabbitmq.publisher-confirm-type: simple).
 * Если метод вернулся без исключения, RabbitMQ принял сообщение и сохранил его в постоянных очередях;
 * без подтверждения сообщение могло пропасть при обрыве соединения, хотя отправка «удалась».
 * <p>
 * Вызывающий отмечает событие отправленным только после успеха, а неотправленные досылает по расписанию:
 * событие доходит хотя бы один раз (получатели готовы к повторам).
 */
public class EventPublisher {

    /** Брокер подтверждает за миллисекунды; дольше — считаем, что не принял. */
    static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final RabbitTemplate rabbit;

    public EventPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    /** @throws AmqpException шина недоступна или не подтвердила приём */
    public void send(String routingKey, Object event) {
        Boolean confirmed = rabbit.invoke(operations -> {
            operations.convertAndSend(Events.EXCHANGE, routingKey, event);
            return operations.waitForConfirms(CONFIRM_TIMEOUT_MS);
        });
        if (!Boolean.TRUE.equals(confirmed)) {
            throw new AmqpException("RabbitMQ не подтвердил приём события " + routingKey);
        }
    }
}
