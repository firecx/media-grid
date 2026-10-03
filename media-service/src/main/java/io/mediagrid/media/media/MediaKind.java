package io.mediagrid.media.media;

import java.util.Locale;
import java.util.Optional;

/** Вид файла; определяется по типу содержимого. */
public enum MediaKind {
    IMAGE,
    VIDEO,
    AUDIO;

    /** По типу содержимого вида image/png, video/mp4; audio/mpeg; codecs=... — только поддерживаемые виды. */
    public static Optional<MediaKind> fromContentType(String contentType) {
        String type = normalizeContentType(contentType);
        if (type.startsWith("image/")) {
            return Optional.of(IMAGE);
        }
        if (type.startsWith("video/")) {
            return Optional.of(VIDEO);
        }
        if (type.startsWith("audio/")) {
            return Optional.of(AUDIO);
        }
        return Optional.empty();
    }

    /** Тип содержимого без параметров и в нижнем регистре. */
    public static String normalizeContentType(String contentType) {
        int params = contentType.indexOf(';');
        String type = params >= 0 ? contentType.substring(0, params) : contentType;
        return type.trim().toLowerCase(Locale.ROOT);
    }
}
