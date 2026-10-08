package io.mediagrid.media.events;

import java.time.Duration;
import java.time.Instant;

import io.mediagrid.common.events.Events;
import io.mediagrid.common.events.MediaDeletedEvent;
import io.mediagrid.support.events.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Событие удаления уходит в шину только после фиксации транзакции: если удаление записи откатилось,
 * хранилище не получит команду удалить файл. Чтобы событие не потерялось при недоступной шине, в той же
 * транзакции записывается отметка (deletion_notices); она снимается после подтверждения шины, а оставшиеся
 * досылаются по расписанию.
 */
@Component
public class MediaEventPublisher {

    /** Досылаются отметки старше этого: более свежие ещё может отправлять само удаление. */
    static final Duration RESEND_AFTER = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(MediaEventPublisher.class);

    private final EventPublisher publisher;
    private final DeletionNoticeRepository notices;

    public MediaEventPublisher(EventPublisher publisher, DeletionNoticeRepository notices) {
        this.publisher = publisher;
        this.notices = notices;
    }

    /** В транзакции удаления: отметка фиксируется вместе с ним или не фиксируется вовсе. */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT, fallbackExecution = true)
    public void remember(MediaDeletedEvent event) {
        notices.save(new DeletionNotice(event.mediaId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMediaDeleted(MediaDeletedEvent event) {
        send(event);
    }

    /** Раз в 30 секунд досылает события удаления, которые шина не подтвердила. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public synchronized void sendPending() {
        for (DeletionNotice notice : notices.findTop100ByCreatedAtBeforeOrderByCreatedAt(
                Instant.now().minus(RESEND_AFTER))) {
            if (!send(new MediaDeletedEvent(notice.getMediaId()))) {
                return;
            }
            log.info("Дослано событие удаления файла {}", notice.getMediaId());
        }
    }

    private boolean send(MediaDeletedEvent event) {
        try {
            publisher.send(Events.MEDIA_DELETED, event);
        } catch (AmqpException e) {
            log.warn("Не удалось отправить событие удаления файла {}, повтор позже: {}", event.mediaId(),
                    e.toString());
            return false;
        }
        notices.sent(event.mediaId());
        return true;
    }
}
