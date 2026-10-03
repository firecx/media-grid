package io.mediagrid.common.events;

/**
 * Устройство шины сообщений. Все события публикуются в одну тематическую точку обмена
 * с ключом маршрутизации по виду события. Каждая служба-получатель объявляет свою
 * постоянную очередь (имя: служба.событие) и привязывает её к нужному ключу;
 * сообщения, которые не удалось обработать, уходят в очередь служба.событие.dlq.
 */
public final class Events {

    /** Точка обмена для всех событий системы. */
    public static final String EXCHANGE = "mediagrid.events";

    /** Точка обмена для сообщений, которые не удалось обработать. */
    public static final String DEAD_LETTER_EXCHANGE = "mediagrid.events.dlx";

    /** {@link FileUploadedEvent}: служба хранения сохранила файл. */
    public static final String FILE_UPLOADED = "file.uploaded";

    /** {@link ProcessingCompletedEvent}: служба обработки закончила работу с файлом. */
    public static final String PROCESSING_COMPLETED = "processing.completed";

    /** {@link MediaDeletedEvent}: служба медиаданных удалила запись о файле. */
    public static final String MEDIA_DELETED = "media.deleted";

    private Events() {
    }
}
