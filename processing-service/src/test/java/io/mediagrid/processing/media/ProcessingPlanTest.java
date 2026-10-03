package io.mediagrid.processing.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mediagrid.processing.job.JobFailure;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Что делать с файлом — по ответу ffprobe (образцы в том виде, в каком его выдаёт ffprobe). */
class ProcessingPlanTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void browserFriendlyMp4IsNotTranscoded() {
        Probe probe = probe("mov,mp4,m4a,3gp,3g2,mj2", "120.500000",
                video(0, "h264", "yuv420p", 1920, 1080), audio(1, "aac"));
        ProcessingPlan plan = ProcessingPlan.of("video/mp4", probe);

        assertThat(plan.transcode()).isFalse();
        assertThat(plan.snapshotStream()).isZero();
        assertThat(plan.snapshotAt()).isEqualTo(10.0);
        assertThat(probe.durationMs()).isEqualTo(120_500);
        assertThat(probe.video().width()).isEqualTo(1920);
    }

    @Test
    void shortVideoSnapshotIsTakenAtTenPercent() {
        ProcessingPlan plan = ProcessingPlan.of("video/mp4",
                probe("mov,mp4,m4a,3gp,3g2,mj2", "5.000000", video(0, "h264", "yuv420p", 640, 360)));
        assertThat(plan.snapshotAt()).isEqualTo(0.5);
    }

    @Test
    void h264InMatroskaIsCopiedAndOnlyAudioIsConverted() {
        ProcessingPlan plan = ProcessingPlan.of("video/x-matroska",
                probe("matroska,webm", "60.0", video(0, "h264", "yuv420p", 1280, 720), audio(1, "opus")));

        assertThat(plan.transcode()).isTrue();
        assertThat(plan.transcodeArgs()).containsSequence("-map", "0:0", "-c:v", "copy")
                .containsSequence("-map", "0:1", "-c:a", "aac")
                .containsSequence("-movflags", "+faststart");
        assertThat(plan.playbackContentType()).isEqualTo("video/mp4");
        assertThat(plan.playbackExtension()).isEqualTo(".mp4");
    }

    @Test
    void hevcAndUnusualPixelFormatsAreReencoded() {
        ProcessingPlan hevc = ProcessingPlan.of("video/quicktime",
                probe("mov,mp4,m4a,3gp,3g2,mj2", "10.0", video(0, "hevc", "yuv420p10le", 3840, 2160), audio(1, "aac")));
        assertThat(hevc.transcodeArgs()).containsSequence("-c:v", "libx264").containsSequence("-c:a", "copy");

        ProcessingPlan yuv444 = ProcessingPlan.of("video/mp4",
                probe("mov,mp4,m4a,3gp,3g2,mj2", "10.0", video(0, "h264", "yuv444p", 640, 480)));
        assertThat(yuv444.transcodeArgs()).containsSequence("-pix_fmt", "yuv420p");
    }

    @Test
    void mp4ContainerWithWrongDeclaredTypeIsRemuxed() {
        // Браузер смотрит на тип, с которым файл выдаётся: video/quicktime Firefox не воспроизводит
        ProcessingPlan plan = ProcessingPlan.of("video/quicktime",
                probe("mov,mp4,m4a,3gp,3g2,mj2", "10.0", video(0, "h264", "yuv420p", 640, 480), audio(1, "aac")));
        assertThat(plan.transcodeArgs()).containsSequence("-c:v", "copy").containsSequence("-c:a", "copy");
    }

    @Test
    void commonAudioFormatsPlayAsIs() {
        assertThat(ProcessingPlan.of("audio/mpeg", probe("mp3", "200.0", audio(0, "mp3"))).transcode()).isFalse();
        assertThat(ProcessingPlan.of("audio/mp4", probe("mov,mp4,m4a,3gp,3g2,mj2", "200.0", audio(0, "aac")))
                .transcode()).isFalse();
        assertThat(ProcessingPlan.of("audio/flac", probe("flac", "200.0", audio(0, "flac"))).transcode()).isFalse();
        assertThat(ProcessingPlan.of("audio/wav", probe("wav", "200.0", audio(0, "pcm_s16le"))).transcode())
                .isFalse();
    }

    @Test
    void otherAudioIsConvertedToAacAndCoverBecomesPreview() {
        Probe probe = probe("ogg", "180.0", audio(0, "vorbis"), cover(1));
        ProcessingPlan plan = ProcessingPlan.of("audio/ogg", probe);

        assertThat(plan.transcodeArgs()).containsSequence("-map", "0:0", "-vn", "-c:a", "aac");
        assertThat(plan.playbackContentType()).isEqualTo("audio/mp4");
        assertThat(plan.playbackExtension()).isEqualTo(".m4a");
        assertThat(plan.snapshotStream()).isEqualTo(1);
        assertThat(probe.video()).isNull();
        assertThat(probe.cover().index()).isEqualTo(1);

        assertThat(ProcessingPlan.of("audio/mpeg", probe("mp3", "1.0", audio(0, "mp3"))).snapshot()).isFalse();
    }

    @Test
    void imagesGetOnlySnapshots() {
        Probe probe = probe("image2", null, video(0, "mjpeg", "yuvj420p", 4000, 3000));
        ProcessingPlan plan = ProcessingPlan.of("image/jpeg", probe);

        assertThat(plan.transcode()).isFalse();
        assertThat(plan.snapshotStream()).isZero();
        assertThat(plan.snapshotAt()).isZero();
        assertThat(probe.durationMs()).isNull();
    }

    @Test
    void filesWithoutUsableStreamsFail() {
        assertThatThrownBy(() -> ProcessingPlan.of("image/png", probe("png_pipe", null, audio(0, "aac"))))
                .isInstanceOf(JobFailure.Permanent.class);
        assertThatThrownBy(() -> ProcessingPlan.of("video/mp4", probe("mov,mp4,m4a,3gp,3g2,mj2", "1.0")))
                .isInstanceOf(JobFailure.Permanent.class);
        assertThatThrownBy(() -> ProcessingPlan.of("audio/mpeg", probe("mp3", "1.0", cover(0))))
                .isInstanceOf(JobFailure.Permanent.class);
        assertThatThrownBy(() -> ProcessingPlan.of("application/pdf", probe("pdf", null)))
                .isInstanceOf(JobFailure.Permanent.class);
    }

    @Test
    void unknownDurationAndBrokenOutputAreHandled() {
        assertThat(probe("matroska,webm", "N/A", video(0, "vp9", "yuv420p", 640, 360)).durationMs()).isNull();
        assertThatThrownBy(() -> Probe.parse("не JSON", json)).isInstanceOf(JobFailure.Permanent.class);
    }

    // --- ответ ffprobe ---

    private Probe probe(String format, String duration, String... streams) {
        String durationField = duration == null ? "" : ", \"duration\": \"" + duration + "\"";
        return Probe.parse("{\"streams\": [" + String.join(", ", streams) + "], \"format\": {\"format_name\": \""
                + format + "\"" + durationField + ", \"nb_streams\": " + streams.length + "}}", json);
    }

    private static String video(int index, String codec, String pixelFormat, int width, int height) {
        return "{\"index\": " + index + ", \"codec_name\": \"" + codec + "\", \"codec_type\": \"video\", "
                + "\"width\": " + width + ", \"height\": " + height + ", \"pix_fmt\": \"" + pixelFormat + "\", "
                + "\"disposition\": {\"default\": 1, \"attached_pic\": 0}}";
    }

    private static String audio(int index, String codec) {
        return "{\"index\": " + index + ", \"codec_name\": \"" + codec + "\", \"codec_type\": \"audio\", "
                + "\"sample_rate\": \"44100\", \"channels\": 2, \"disposition\": {\"default\": 1}}";
    }

    private static String cover(int index) {
        return "{\"index\": " + index + ", \"codec_name\": \"mjpeg\", \"codec_type\": \"video\", \"width\": 600, "
                + "\"height\": 600, \"pix_fmt\": \"yuvj420p\", \"disposition\": {\"attached_pic\": 1}}";
    }
}
