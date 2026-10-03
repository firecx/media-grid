package io.mediagrid.media.media;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MediaFileRepository extends JpaRepository<MediaFile, UUID>, JpaSpecificationExecutor<MediaFile> {

    List<MediaFile> findTop100ByStatusAndCreatedAtBefore(MediaStatus status, Instant before);
}
