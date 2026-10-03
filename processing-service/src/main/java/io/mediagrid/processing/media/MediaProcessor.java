package io.mediagrid.processing.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.mediagrid.processing.config.ProcessingProperties;
import io.mediagrid.processing.job.JobContext;
import io.mediagrid.processing.job.JobFailure;
import io.mediagrid.processing.job.JobStage;
import io.mediagrid.processing.job.JobTicket;
import io.mediagrid.processing.job.ProcessingResult;
import io.mediagrid.processing.storage.StorageClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

/**
 * Обработка одного файла: получить исходник из службы хранения, разобрать, сделать миниатюру и превью,
 * при необходимости перекодировать, сохранить результаты в службу хранения. Временные файлы — в
 * отдельном каталоге задачи, который удаляется в конце при любом исходе.
 */
@Component
public class MediaProcessor {

    static final String JPEG = "image/jpeg";

    private static final Logger log = LoggerFactory.getLogger(MediaProcessor.class);

    private final StorageClient storage;
    private final MediaTools tools;
    private final ProcessingProperties properties;

    public MediaProcessor(StorageClient storage, MediaTools tools, ProcessingProperties properties) {
        this.storage = storage;
        this.tools = tools;
        this.properties = properties;
    }

    public ProcessingResult process(JobTicket ticket, JobContext context) {
        Path dir = properties.workDir().resolve(ticket.mediaId().toString());
        try {
            FileSystemUtils.deleteRecursively(dir);
            Files.createDirectories(dir);
            return process(ticket, context, dir);
        } catch (IOException e) {
            throw new JobFailure.Transient("Ошибка временного каталога: " + e.getMessage(), e);
        } finally {
            try {
                FileSystemUtils.deleteRecursively(dir);
            } catch (IOException e) {
                log.warn("Не удалось удалить временный каталог {}: {}", dir, e.toString());
            }
        }
    }

    private ProcessingResult process(JobTicket ticket, JobContext context, Path dir) {
        context.report(JobStage.DOWNLOADING, 0);
        Path original = dir.resolve("original");
        storage.downloadOriginal(ticket.mediaId(), original);

        context.report(JobStage.ANALYZING, 10);
        Probe probe = tools.probe(original, context);
        ProcessingPlan plan = ProcessingPlan.of(ticket.contentType(), probe);

        Path preview = dir.resolve("preview.jpg");
        Path thumbnail = dir.resolve("thumbnail.jpg");
        boolean hasPreview = plan.snapshot() && snapshots(plan, original, preview, thumbnail, context);

        Path playback = null;
        if (plan.transcode()) {
            context.report(JobStage.TRANSCODING, 20);
            playback = dir.resolve("playback" + plan.playbackExtension());
            tools.transcode(original, plan.transcodeArgs(), probe.durationMs(), playback, context,
                    percent -> context.report(JobStage.TRANSCODING, 20 + percent * 70 / 100));
        }

        context.report(JobStage.SAVING, 92);
        if (hasPreview) {
            storage.storeVariant(ticket.mediaId(), "thumbnail", thumbnail, JPEG);
            storage.storeVariant(ticket.mediaId(), "preview", preview, JPEG);
        }
        if (playback != null) {
            context.report(JobStage.SAVING, 95);
            storage.storeVariant(ticket.mediaId(), "playback", playback, plan.playbackContentType());
        }
        Probe.Stream video = plan.kind() == MediaKind.AUDIO ? null : probe.video();
        return new ProcessingResult(plan.kind() == MediaKind.IMAGE ? null : probe.durationMs(),
                video != null ? video.width() : null, video != null ? video.height() : null,
                video != null ? video.codec() : null, probe.audio() != null ? probe.audio().codec() : null,
                plan.transcode(), hasPreview);
    }

    /**
     * Превью и миниатюра. Для изображения без них обработка не удалась; у видео и звука это не главное —
     * без превью файл всё равно воспроизводится.
     */
    private boolean snapshots(ProcessingPlan plan, Path original, Path preview, Path thumbnail,
                              JobContext context) {
        context.report(JobStage.PREVIEW, 15);
        try {
            snapshot(plan, original, properties.previewSize(), preview, context);
            snapshot(plan, original, properties.thumbnailSize(), thumbnail, context);
            return true;
        } catch (JobFailure.Permanent e) {
            if (plan.kind() == MediaKind.IMAGE) {
                throw e;
            }
            log.warn("Файл {}: превью не получено: {}", context.mediaId(), e.getMessage());
            return false;
        }
    }

    /** У коротких видео кадр с 10 % длительности может не найтись — тогда берётся первый. */
    private void snapshot(ProcessingPlan plan, Path original, int size, Path output, JobContext context) {
        try {
            tools.snapshot(original, plan.snapshotStream(), plan.snapshotAt(), size, output, context);
        } catch (JobFailure.Permanent e) {
            if (plan.snapshotAt() <= 0) {
                throw e;
            }
            tools.snapshot(original, plan.snapshotStream(), 0, size, output, context);
        }
    }
}
