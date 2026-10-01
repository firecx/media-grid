package io.mediagrid.common.events;

import java.util.UUID;

/** Событие: файл загружен в хранилище и ждёт обработки. */
public record FileUploadedEvent(UUID mediaId, String storageKey, String contentType, long sizeBytes) {
}