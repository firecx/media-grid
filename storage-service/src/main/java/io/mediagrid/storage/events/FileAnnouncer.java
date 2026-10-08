package io.mediagrid.storage.events;

import java.time.Duration;
import java.time.Instant;

import io.mediagrid.common.events.Events;
import io.mediagrid.common.events.FileUploadedEvent;
import io.mediagrid.storage.file.FileStatus;
import io.mediagrid.storage.file.StoredFile;
import io.mediagrid.storage.file.StoredFileRepository;
import io.mediagrid.storage.upload.UploadService;
import io.mediagrid.support.events.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Сообщает каталогу о полученных файлах (file.uploaded). Файл отмечается сообщённым (announced_at) только
 * после подтверждения шины; что не ушло, досылается по расписанию. Иначе при недоступной шине запись
 * каталога осталась бы «ожидающей загрузки» и через сутки была бы удалена вместе с полученным файлом.
 */
@Component
public class FileAnnouncer {

    /** Досылаются события старше этого: более свежие ещё может отправлять сама загрузка. */
    static final Duration RESEND_AFTER = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(FileAnnouncer.class);

    private final EventPublisher publisher;
    private final StoredFileRepository storedFiles;

    public FileAnnouncer(EventPublisher publisher, StoredFileRepository storedFiles) {
        this.publisher = publisher;
        this.storedFiles = storedFiles;
    }

    /** Файл получен целиком — сообщаем после фиксации транзакции. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFileUploaded(FileUploadedEvent event) {
        announce(event);
    }

    /** Раз в 30 секунд досылает события о файлах, получение которых шина не подтвердила. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public synchronized void announcePending() {
        for (StoredFile file : storedFiles.findTop100ByStatusAndAnnouncedAtIsNullAndCompletedAtBeforeOrderByCompletedAt(
                FileStatus.COMPLETE, Instant.now().minus(RESEND_AFTER))) {
            if (!announce(UploadService.uploadedEvent(file))) {
                return;
            }
            log.info("Дослано событие о загрузке файла {}", file.getMediaId());
        }
    }

    private boolean announce(FileUploadedEvent event) {
        try {
            publisher.send(Events.FILE_UPLOADED, event);
        } catch (AmqpException e) {
            log.warn("Не удалось отправить событие о загрузке файла {}, повтор позже: {}", event.mediaId(),
                    e.toString());
            return false;
        }
        storedFiles.markAnnounced(event.mediaId(), Instant.now());
        return true;
    }
}
