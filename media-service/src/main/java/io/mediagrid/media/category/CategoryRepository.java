package io.mediagrid.media.category;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    @Query("select count(c) > 0 from Category c where lower(c.name) = lower(:name) and (:exceptId is null or c.id <> :exceptId)")
    boolean existsByNameIgnoreCaseExcept(String name, UUID exceptId);
}
