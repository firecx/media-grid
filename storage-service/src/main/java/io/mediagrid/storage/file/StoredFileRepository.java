package io.mediagrid.storage.file;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

    /** Полученные файлы, о которых каталогу ещё не сообщено. */
    List<StoredFile> findTop100ByStatusAndAnnouncedAtIsNullAndCompletedAtBeforeOrderByCompletedAt(FileStatus status,
                                                                                                    Instant before);

    /** Своя транзакция: вызывается после фиксации основной, когда шина подтвердила приём события. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query("update StoredFile f set f.announcedAt = :now where f.mediaId = :mediaId and f.announcedAt is null")
    int markAnnounced(UUID mediaId, Instant now);
}
