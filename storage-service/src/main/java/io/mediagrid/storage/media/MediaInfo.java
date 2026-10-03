package io.mediagrid.storage.media;

import java.util.UUID;

/** То, что служба хранения берёт из записи каталога (ответ GET /api/media/{id}); остальные поля не читаются. */
public record MediaInfo(UUID id, UUID ownerId, String originalFilename, String contentType, long sizeBytes,
                        String status) {

    /** Запись ждёт загрузки файла. */
    public boolean awaitingUpload() {
        return "PENDING_UPLOAD".equals(status);
    }
}
