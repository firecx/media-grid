package io.mediagrid.processing.job;

import io.mediagrid.common.events.Events;
import io.mediagrid.common.events.ProcessingCompletedEvent;
import io.mediagrid.support.events.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Отправка processing.completed. Итог сначала фиксируется в базе, затем отправляется; что не ушло
 * (шина недоступна или не подтвердила приём), досылается по расписанию. Каталог получит итог хотя бы один раз.
 */
@Component
public class JobNotifier {

    private static final Logger log = LoggerFactory.getLogger(JobNotifier.class);

    private final JobService jobs;
    private final EventPublisher publisher;

    public JobNotifier(JobService jobs, EventPublisher publisher) {
        this.jobs = jobs;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public synchronized void sendPending() {
        for (Job job : jobs.unnotified()) {
            ProcessingCompletedEvent event = new ProcessingCompletedEvent(job.getMediaId(),
                    job.getStatus() == JobStatus.DONE, job.isHasPreview(), job.getError());
            try {
                publisher.send(Events.PROCESSING_COMPLETED, event);
            } catch (AmqpException e) {
                log.warn("Не удалось отправить итог обработки {}, повтор позже: {}", job.getMediaId(), e.toString());
                return;
            }
            jobs.markNotified(job.getMediaId());
        }
    }
}
