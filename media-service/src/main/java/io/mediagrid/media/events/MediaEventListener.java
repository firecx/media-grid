package io.mediagrid.media.events;

import io.mediagrid.common.events.FileUploadedEvent;
import io.mediagrid.common.events.ProcessingCompletedEvent;
import io.mediagrid.media.media.MediaService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Приём событий от служб хранения и обработки. При ошибке сообщение повторяется несколько раз
 * (spring.rabbitmq.listener.simple.retry), затем уходит в очередь .dlq для разбора.
 */
@Component
public class MediaEventListener {

    private final MediaService media;

    public MediaEventListener(MediaService media) {
        this.media = media;
    }

    @RabbitListener(queues = RabbitConfig.FILE_UPLOADED_QUEUE)
    public void onFileUploaded(FileUploadedEvent event) {
        media.onFileUploaded(event);
    }

    @RabbitListener(queues = RabbitConfig.PROCESSING_COMPLETED_QUEUE)
    public void onProcessingCompleted(ProcessingCompletedEvent event) {
        media.onProcessingCompleted(event);
    }
}
