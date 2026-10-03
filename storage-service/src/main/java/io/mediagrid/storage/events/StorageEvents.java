package io.mediagrid.storage.events;

import java.io.IOException;

import io.mediagrid.common.events.Events;
import io.mediagrid.common.events.FileUploadedEvent;
import io.mediagrid.common.events.MediaDeletedEvent;
import io.mediagrid.storage.file.FileService;
import io.mediagrid.support.events.EventQueues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** События службы хранения: публикует file.uploaded, получает media.deleted (см. {@link Events}). */
@Configuration
public class StorageEvents {

    static final String SERVICE = "storage-service";
    static final String MEDIA_DELETED_QUEUE = SERVICE + "." + Events.MEDIA_DELETED;

    private static final Logger log = LoggerFactory.getLogger(StorageEvents.class);

    private final RabbitTemplate rabbit;
    private final FileService files;

    public StorageEvents(RabbitTemplate rabbit, FileService files) {
        this.rabbit = rabbit;
        this.files = files;
    }

    @Bean
    Declarables storageServiceQueues() {
        return EventQueues.declare(SERVICE, Events.MEDIA_DELETED);
    }

    /**
     * Файл получен целиком — сообщаем после фиксации транзакции. Если отправить не удалось, каталог
     * узнает о файле при следующем запросе загрузки (UploadService повторит событие).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFileUploaded(FileUploadedEvent event) {
        try {
            rabbit.convertAndSend(Events.EXCHANGE, Events.FILE_UPLOADED, event);
        } catch (AmqpException e) {
            log.error("Не удалось отправить событие о загрузке файла {}", event.mediaId(), e);
        }
    }

    /** Запись каталога удалена — удаляем файл. При сбое сообщение повторится, затем уйдёт в .dlq. */
    @RabbitListener(queues = MEDIA_DELETED_QUEUE)
    public void onMediaDeleted(MediaDeletedEvent event) throws IOException {
        files.delete(event.mediaId());
    }
}
