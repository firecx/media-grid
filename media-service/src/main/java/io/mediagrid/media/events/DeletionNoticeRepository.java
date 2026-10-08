package io.mediagrid.media.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface DeletionNoticeRepository extends JpaRepository<DeletionNotice, UUID> {

    List<DeletionNotice> findTop100ByCreatedAtBeforeOrderByCreatedAt(Instant before);

    /** Своя транзакция: вызывается после фиксации удаления, когда шина подтвердила приём события. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query("delete from DeletionNotice n where n.mediaId = :mediaId")
    int sent(UUID mediaId);
}
