package io.mediagrid.media.events;

import io.mediagrid.common.events.Events;
import io.mediagrid.common.events.MediaDeletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Отправляет событие удаления в шину только после фиксации транзакции: если удаление записи
 * откатилось, хранилище не получит команду удалить файл.
 */
@Component
public class MediaEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(MediaEventPublisher.class);

    private final RabbitTemplate rabbit;

    public MediaEventPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMediaDeleted(MediaDeletedEvent event) {
        try {
            rabbit.convertAndSend(Events.EXCHANGE, Events.MEDIA_DELETED, event);
        } catch (AmqpException e) {
            // Запись уже удалена; в хранилище останется лишний файл, но не запись без файла (ТЗ, п. 4.1.9)
            log.error("Не удалось отправить событие удаления файла {}: файл останется в хранилище",
                    event.mediaId(), e);
        }
    }
}
