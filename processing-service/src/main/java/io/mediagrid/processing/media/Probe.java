package io.mediagrid.processing.media;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.mediagrid.processing.job.JobFailure;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Что внутри файла — по ответу ffprobe: формат, длительность, первые дорожки изображения и звука,
 * обложка (картинка, вложенная в звуковой файл).
 *
 * @param formatName названия формата через запятую, как их даёт ffprobe (например, «mov,mp4,m4a,3gp,3g2,mj2»)
 * @param durationMs длительность; null у фотографий и если ffprobe её не знает
 */
public record Probe(String formatName, Long durationMs, Stream video, Stream audio, Stream cover) {

    /** Дорожка файла; index — её номер для ffmpeg (-map 0:index). */
    public record Stream(int index, String codec, Integer width, Integer height, String pixelFormat) {
    }

    public boolean hasFormat(String name) {
        return formatName != null && List.of(formatName.split(",")).contains(name);
    }

    static Probe parse(String json, JsonMapper mapper) {
        Output output;
        try {
            output = mapper.readValue(json, Output.class);
        } catch (JacksonException e) {
            throw new JobFailure.Permanent("Не удалось разобрать ответ ffprobe: " + e.getOriginalMessage());
        }
        Stream video = null;
        Stream audio = null;
        Stream cover = null;
        Long streamDuration = null;
        for (RawStream raw : output.streams() == null ? List.<RawStream>of() : output.streams()) {
            Stream stream = new Stream(raw.index(), raw.codecName(), raw.width(), raw.height(), raw.pixFmt());
            if ("video".equals(raw.codecType())) {
                if (raw.isAttachedPicture()) {
                    cover = cover == null ? stream : cover;
                } else if (video == null) {
                    video = stream;
                    streamDuration = millis(raw.duration());
                }
            } else if ("audio".equals(raw.codecType()) && audio == null) {
                audio = stream;
                streamDuration = streamDuration == null ? millis(raw.duration()) : streamDuration;
            }
        }
        Format format = output.format();
        Long duration = format == null ? null : millis(format.duration());
        return new Probe(format == null ? null : format.formatName(), duration != null ? duration : streamDuration,
                video, audio, cover);
    }

    /** ffprobe пишет длительность в секундах строкой: «12.345000» или «N/A». */
    private static Long millis(String seconds) {
        if (seconds == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(seconds);
            return value > 0 ? Math.round(value * 1000) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Ответ ffprobe -print_format json -show_format -show_streams; лишние поля пропускаются

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Output(List<RawStream> streams, Format format) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawStream(int index,
                     @JsonProperty("codec_type") String codecType,
                     @JsonProperty("codec_name") String codecName,
                     Integer width,
                     Integer height,
                     @JsonProperty("pix_fmt") String pixFmt,
                     String duration,
                     Map<String, Integer> disposition) {

        boolean isAttachedPicture() {
            return disposition != null && Integer.valueOf(1).equals(disposition.get("attached_pic"));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Format(@JsonProperty("format_name") String formatName, String duration) {
    }
}
