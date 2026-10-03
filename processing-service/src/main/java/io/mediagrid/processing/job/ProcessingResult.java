package io.mediagrid.processing.job;

/**
 * Итог обработки и сведения о файле (метаданные обработки). Неизвестные величины — null:
 * у фотографии нет длительности, у звука — размера кадра.
 */
public record ProcessingResult(Long durationMs, Integer width, Integer height, String videoCodec, String audioCodec,
                               boolean transcoded, boolean hasPreview) {
}
