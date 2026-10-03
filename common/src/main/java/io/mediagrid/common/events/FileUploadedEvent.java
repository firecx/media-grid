package io.mediagrid.common.events;

import java.util.UUID;

/**
 * Событие: файл загружен в хранилище целиком и ждёт обработки. Где именно лежит файл, знает только
 * служба хранения; остальные обращаются к нему по mediaId.
 */
public record FileUploadedEvent(UUID mediaId, UUID ownerId, String contentType, long sizeBytes) {
}
