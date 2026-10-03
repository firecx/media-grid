package io.mediagrid.processing.media;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.mediagrid.processing.job.JobFailure;

/**
 * Что сделать с файлом. Перекодируется только то, что браузер не воспроизведёт; исходный файл
 * остаётся нетронутым в любом случае.
 *
 * @param snapshotStream дорожка, из которой берётся кадр для превью; null — превью не будет
 * @param snapshotAt     с какой секунды брать кадр (у видео — не самый первый, он часто чёрный)
 * @param transcodeArgs  параметры ffmpeg между входом и выходом; null — перекодировать не нужно
 */
public record ProcessingPlan(MediaKind kind, Integer snapshotStream, double snapshotAt, List<String> transcodeArgs,
                             String playbackContentType, String playbackExtension) {

    /** Кадр для превью видео: 10 % длительности, но не дальше этого. */
    static final double MAX_SNAPSHOT_SECONDS = 10;

    /** Видео, которые воспроизводят все распространённые браузеры: H.264 с обычной цветовой схемой. */
    private static final Set<String> PLAYABLE_PIXEL_FORMATS = Set.of("yuv420p", "yuvj420p");
    private static final Set<String> PLAYABLE_AUDIO_IN_MP4 = Set.of("aac", "mp3");

    public boolean snapshot() {
        return snapshotStream != null;
    }

    public boolean transcode() {
        return transcodeArgs != null;
    }

    public static ProcessingPlan of(String contentType, Probe probe) {
        MediaKind kind = MediaKind.of(contentType);
        return switch (kind) {
            case IMAGE -> image(probe);
            case VIDEO -> video(contentType, probe);
            case AUDIO -> audio(probe);
        };
    }

    /** Изображение не перекодируется; превью и миниатюра — из него самого (у GIF — первый кадр). */
    private static ProcessingPlan image(Probe probe) {
        Probe.Stream picture = probe.video() != null ? probe.video() : probe.cover();
        if (picture == null) {
            throw new JobFailure.Permanent("В файле нет изображения");
        }
        return new ProcessingPlan(MediaKind.IMAGE, picture.index(), 0, null, null, null);
    }

    /**
     * Видео воспроизводится как есть, если это MP4 с H.264 (yuv420p) и звуком AAC или MP3. Иначе — MP4
     * с H.264 и AAC; что уже подходит (например, H.264 в MKV), копируется без перекодирования.
     */
    private static ProcessingPlan video(String contentType, Probe probe) {
        Probe.Stream video = probe.video();
        Probe.Stream audio = probe.audio();
        if (video == null && audio == null) {
            throw new JobFailure.Permanent("В файле нет ни изображения, ни звука");
        }
        boolean videoPlayable = video == null || isPlayableH264(video);
        boolean audioPlayable = audio == null || PLAYABLE_AUDIO_IN_MP4.contains(codec(audio));
        boolean mp4 = probe.hasFormat("mp4") && "video/mp4".equals(contentType.toLowerCase(Locale.ROOT));
        Integer snapshot = video != null ? Integer.valueOf(video.index()) : null;
        double at = probe.durationMs() == null
                ? 0
                : Math.min(probe.durationMs() / 1000.0 * 0.1, MAX_SNAPSHOT_SECONDS);
        if (mp4 && videoPlayable && audioPlayable) {
            return new ProcessingPlan(MediaKind.VIDEO, snapshot, at, null, null, null);
        }
        List<String> args = new ArrayList<>();
        if (video != null) {
            args.addAll(List.of("-map", "0:" + video.index()));
            if (videoPlayable) {
                args.addAll(List.of("-c:v", "copy"));
            } else {
                // Чётные стороны кадра — требование yuv420p
                args.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
                        "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2"));
            }
        }
        if (audio != null) {
            args.addAll(List.of("-map", "0:" + audio.index()));
            if ("aac".equals(codec(audio))) {
                args.addAll(List.of("-c:a", "copy"));
            } else {
                args.addAll(List.of("-c:a", "aac", "-b:a", "160k", "-ac", "2"));
            }
        }
        // Оглавление файла в начало: воспроизведение начинается, не дожидаясь загрузки всего файла
        args.addAll(List.of("-sn", "-dn", "-movflags", "+faststart"));
        return new ProcessingPlan(MediaKind.VIDEO, snapshot, at, List.copyOf(args), "video/mp4", ".mp4");
    }

    /**
     * Звук воспроизводится как есть, если это MP3, AAC в MP4 или ADTS, FLAC или WAV (PCM). Остальное
     * (Vorbis, Opus, WMA, AC-3 и т. п.) — в AAC (M4A). Превью — из обложки, если она вложена в файл.
     */
    private static ProcessingPlan audio(Probe probe) {
        Probe.Stream audio = probe.audio();
        if (audio == null) {
            throw new JobFailure.Permanent("В файле нет звука");
        }
        Integer snapshot = probe.cover() != null ? Integer.valueOf(probe.cover().index()) : null;
        String codec = codec(audio);
        boolean playable = ("mp3".equals(codec) && probe.hasFormat("mp3"))
                || ("aac".equals(codec) && (probe.hasFormat("mp4") || probe.hasFormat("aac")))
                || ("flac".equals(codec) && probe.hasFormat("flac"))
                || (codec.startsWith("pcm_") && probe.hasFormat("wav"));
        if (playable) {
            return new ProcessingPlan(MediaKind.AUDIO, snapshot, 0, null, null, null);
        }
        List<String> args = List.of("-map", "0:" + audio.index(), "-vn", "-c:a", "aac", "-b:a", "192k",
                "-movflags", "+faststart");
        return new ProcessingPlan(MediaKind.AUDIO, snapshot, 0, args, "audio/mp4", ".m4a");
    }

    private static boolean isPlayableH264(Probe.Stream video) {
        return "h264".equals(codec(video)) && PLAYABLE_PIXEL_FORMATS.contains(String.valueOf(video.pixelFormat()));
    }

    private static String codec(Probe.Stream stream) {
        return stream.codec() == null ? "" : stream.codec().toLowerCase(Locale.ROOT);
    }
}
