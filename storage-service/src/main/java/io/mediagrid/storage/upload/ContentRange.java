package io.mediagrid.storage.upload;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.mediagrid.support.web.ApiException;

/**
 * Часть файла в запросе загрузки: заголовок {@code Content-Range: bytes <начало>-<конец>/<размер>},
 * концы включительно, как в HTTP.
 */
public record ContentRange(long start, long end, long total) {

    private static final Pattern FORMAT = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

    public long length() {
        return end - start + 1;
    }

    /** Разбор заголовка; null — заголовка нет (загружается весь файл). */
    public static ContentRange parse(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        Matcher m = FORMAT.matcher(header.trim());
        if (!m.matches()) {
            throw invalid();
        }
        try {
            ContentRange range = new ContentRange(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)),
                    Long.parseLong(m.group(3)));
            if (range.start > range.end || range.end >= range.total) {
                throw invalid();
            }
            return range;
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return ApiException.badRequest("INVALID_CONTENT_RANGE",
                "Заголовок Content-Range должен иметь вид bytes <начало>-<конец>/<размер файла>");
    }
}
