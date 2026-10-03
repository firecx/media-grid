package io.mediagrid.storage.file;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FileVariantRepository extends JpaRepository<FileVariant, FileVariant.Key> {

    List<FileVariant> findByMediaId(UUID mediaId);
}
