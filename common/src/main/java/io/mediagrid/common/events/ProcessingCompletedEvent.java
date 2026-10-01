package io.mediagrid.common.events;

import java.util.UUID;

/** Событие: обработка файла завершена (успешно или с ошибкой). */
public record ProcessingCompletedEvent(UUID mediaId, boolean success, String previewKey, String errorMessage) {
}