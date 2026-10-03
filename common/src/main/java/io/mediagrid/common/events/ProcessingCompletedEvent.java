package io.mediagrid.common.events;

import java.util.UUID;

/**
 * Событие: обработка файла завершена (успешно или с ошибкой). Производные файлы (превью,
 * версия для воспроизведения) лежат в службе хранения под тем же mediaId.
 */
public record ProcessingCompletedEvent(UUID mediaId, boolean success, boolean hasPreview, String errorMessage) {
}
