package io.mediagrid.storage.file;

import java.util.Locale;

import io.mediagrid.support.web.ApiException;

/** Что выдаётся по ссылке: исходный файл или один из производных, которые сделала служба обработки. */
public enum VariantKind {

    /** Исходный файл в том виде, в каком его загрузил пользователь. */
    ORIGINAL,
    /** Маленькое изображение для списков (JPEG). */
    THUMBNAIL,
    /** Изображение для просмотра: кадр видео, уменьшенная фотография, обложка альбома (JPEG). */
    PREVIEW,
    /** Версия, которую воспроизводит браузер (MP4 или M4A), если исходный файл он не воспроизводит. */
    PLAYBACK;

    /** Имя в адресе и в подписи ссылки: original, thumbnail, preview, playback. */
    public String pathName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static VariantKind fromPath(String value) {
        for (VariantKind kind : values()) {
            if (kind.pathName().equalsIgnoreCase(value)) {
                return kind;
            }
        }
        throw ApiException.badRequest("INVALID_VARIANT",
                "Неизвестный вид файла: " + value + " (допустимо: original, thumbnail, preview, playback)");
    }
}
