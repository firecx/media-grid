package io.mediagrid.processing.media;

import java.nio.file.Path;
import java.util.List;
import java.util.function.IntConsumer;

import io.mediagrid.processing.job.JobContext;

/**
 * Работа с медиафайлами (сейчас — ffmpeg и ffprobe). Ошибка разбора или обработки —
 * {@link io.mediagrid.processing.job.JobFailure.Permanent}.
 */
public interface MediaTools {

    Probe probe(Path input, JobContext context);

    /** Один кадр дорожки streamIndex с секунды atSeconds в JPEG, большая сторона не больше maxSize. */
    void snapshot(Path input, int streamIndex, double atSeconds, int maxSize, Path output, JobContext context);

    /** Перекодирование с параметрами плана; ход — в процентах от длительности (если она известна). */
    void transcode(Path input, List<String> args, Long durationMs, Path output, JobContext context,
                   IntConsumer percent);
}
