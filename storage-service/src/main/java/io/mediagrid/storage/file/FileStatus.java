package io.mediagrid.storage.file;

/** Состояние файла в хранилище. */
public enum FileStatus {
    /** Файл получен не целиком; загрузку можно продолжить с полученного места. */
    UPLOADING,
    /** Файл получен целиком. */
    COMPLETE
}
