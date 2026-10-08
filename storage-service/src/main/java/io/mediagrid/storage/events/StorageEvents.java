package io.mediagrid.storage.events;

import java.io.IOException;

import io.mediagrid.common.events.Events;
import io.mediagrid.common.events.MediaDeletedEvent;
import io.mediagrid.storage.file.FileService;
import io.mediagrid.support.events.EventQueues;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** События службы хранения: получает media.deleted (см. {@link Events}); file.uploaded отправляет FileAnnouncer. */
@Configuration
public class StorageEvents {

    static final String SERVICE = "storage-service";
    static final String MEDIA_DELETED_QUEUE = SERVICE + "." + Events.MEDIA_DELETED;

    private final FileService files;

    public StorageEvents(FileService files) {
        this.files = files;
    }

    @Bean
    Declarables storageServiceQueues() {
        return EventQueues.declare(SERVICE, Events.MEDIA_DELETED);
    }

    /** Запись каталога удалена — удаляем файл. При сбое сообщение повторится, затем уйдёт в .dlq. */
    @RabbitListener(queues = MEDIA_DELETED_QUEUE)
    public void onMediaDeleted(MediaDeletedEvent event) throws IOException {
        files.delete(event.mediaId());
    }
}
