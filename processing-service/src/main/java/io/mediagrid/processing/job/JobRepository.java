package io.mediagrid.processing.job;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface JobRepository extends JpaRepository<Job, UUID> {

    /**
     * Следующая задача, которую можно брать. Строка блокируется до конца транзакции, а занятые другими
     * исполнителями пропускаются (SKIP LOCKED): несколько экземпляров службы не возьмут одну задачу дважды.
     */
    @Query(value = """
            SELECT * FROM jobs
            WHERE status = 'QUEUED' AND next_attempt_at <= now()
            ORDER BY next_attempt_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Job> lockNextQueued();

    /** Отметка исполнителя и ход работы. 0 — задачи больше нет или она уже не выполняется. */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Job j SET j.stage = :stage, j.progress = :progress, j.heartbeatAt = :now
            WHERE j.mediaId = :mediaId AND j.status = io.mediagrid.processing.job.JobStatus.RUNNING
            """)
    int updateProgress(UUID mediaId, JobStage stage, int progress, Instant now);

    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Job j SET j.heartbeatAt = :now
            WHERE j.mediaId = :mediaId AND j.status = io.mediagrid.processing.job.JobStatus.RUNNING
            """)
    int heartbeat(UUID mediaId, Instant now);

    List<Job> findTop100ByStatusAndHeartbeatAtBefore(JobStatus status, Instant before);

    @Query("""
            SELECT j FROM Job j
            WHERE j.notified = false
              AND j.status IN (io.mediagrid.processing.job.JobStatus.DONE, io.mediagrid.processing.job.JobStatus.FAILED)
            ORDER BY j.finishedAt
            LIMIT 100
            """)
    List<Job> findUnnotified();

    Page<Job> findByStatus(JobStatus status, Pageable pageable);
}
