package io.mediagrid.media.media;

/** Кому виден файл, кроме владельца и администратора. */
public enum Visibility {
    /** Только владельцу. */
    PRIVATE,
    /** Всем вошедшим пользователям. */
    PUBLIC
}
