package io.mediagrid.processing.media;

import java.util.Locale;

import io.mediagrid.processing.job.JobFailure;

/** Вид файла по типу содержимого (каталог принимает только image/*, video/*, audio/*). */
public enum MediaKind {
    IMAGE, VIDEO, AUDIO;

    public static MediaKind of(String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.startsWith("image/")) {
            return IMAGE;
        }
        if (type.startsWith("video/")) {
            return VIDEO;
        }
        if (type.startsWith("audio/")) {
            return AUDIO;
        }
        throw new JobFailure.Permanent("Неподдерживаемый тип файла: " + contentType);
    }
}
