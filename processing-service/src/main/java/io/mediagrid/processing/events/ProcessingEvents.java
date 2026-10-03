package io.mediagrid.processing.events;

import io.mediagrid.common.events.Events;
import io.mediagrid.common.events.FileUploadedEvent;
import io.mediagrid.common.events.MediaDeletedEvent;
import io.mediagrid.processing.job.JobRunner;
import io.mediagrid.processing.job.JobService;
import io.mediagrid.support.events.EventQueues;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * События службы обработки (см. {@link Events}): file.uploaded ставит задачу в очередь в базе,
 * media.deleted её снимает. Сами сообщения подтверждаются сразу: долгая обработка идёт уже не
 * из шины, поэтому не упирается в ограничение RabbitMQ на время подтверждения.
 */
@Configuration
public class ProcessingEvents {

    static final String SERVICE = "processing-service";
    static final String FILE_UPLOADED_QUEUE = SERVICE + "." + Events.FILE_UPLOADED;
    static final String MEDIA_DELETED_QUEUE = SERVICE + "." + Events.MEDIA_DELETED;

    private final JobService jobs;
    private final JobRunner runner;

    public ProcessingEvents(JobService jobs, JobRunner runner) {
        this.jobs = jobs;
        this.runner = runner;
    }

    @Bean
    Declarables processingServiceQueues() {
        return EventQueues.declare(SERVICE, Events.FILE_UPLOADED, Events.MEDIA_DELETED);
    }

    @RabbitListener(queues = FILE_UPLOADED_QUEUE)
    public void onFileUploaded(FileUploadedEvent event) {
        if (jobs.enqueue(event)) {
            runner.dispatch();
        }
    }

    @RabbitListener(queues = MEDIA_DELETED_QUEUE)
    public void onMediaDeleted(MediaDeletedEvent event) {
        jobs.remove(event.mediaId());
        runner.cancel(event.mediaId());
    }
}
