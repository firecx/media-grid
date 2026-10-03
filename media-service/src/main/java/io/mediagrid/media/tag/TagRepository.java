package io.mediagrid.media.tag;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TagRepository extends JpaRepository<Tag, UUID> {

    List<Tag> findByNameIn(Collection<String> names);

    /** Создаёт тег, если его ещё нет; безопасно при одновременных запросах. */
    @Modifying
    @Query(value = "INSERT INTO tags (id, name) VALUES (:id, :name) ON CONFLICT (name) DO NOTHING",
            nativeQuery = true)
    void insertIfAbsent(UUID id, String name);
}
