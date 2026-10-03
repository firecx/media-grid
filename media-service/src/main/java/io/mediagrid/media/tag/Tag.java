package io.mediagrid.media.tag;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Тег; создаётся при первом использовании, имя в нижнем регистре. */
@Entity
@Table(name = "tags")
public class Tag {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    protected Tag() {
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
