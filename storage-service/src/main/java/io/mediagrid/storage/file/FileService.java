package io.mediagrid.storage.file;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import io.mediagrid.storage.config.StorageProperties;
import io.mediagrid.storage.link.LinkSigner;
import io.mediagrid.storage.media.MediaClient;
import io.mediagrid.storage.provider.StorageProvider;
import io.mediagrid.storage.upload.UploadService;
import io.mediagrid.support.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

/** Ссылки на файл, выдача по ссылке и удаление. */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final StoredFileRepository files;
    private final StorageProvider storage;
    private final MediaClient media;
    private final LinkSigner signer;
    private final StorageProperties properties;

    public FileService(StoredFileRepository files, StorageProvider storage, MediaClient media, LinkSigner signer,
                       StorageProperties properties) {
        this.files = files;
        this.storage = storage;
        this.media = media;
        this.signer = signer;
        this.properties = properties;
    }

    /**
     * Ссылка с ограниченным сроком действия (ТЗ, п. 4.1.5). Право на неё проверяет служба медиаданных:
     * ссылку получит только тот, кому запись видна.
     */
    public Link createLink(UUID mediaId, String authorization, boolean download) {
        media.get(mediaId, authorization);
        StoredFile file = files.findById(mediaId)
                .filter(StoredFile::isComplete)
                .orElseThrow(() -> ApiException.conflict("NOT_UPLOADED", "Файл ещё не загружен"));
        Instant expires = Instant.now().plus(properties.linkTtl()).truncatedTo(ChronoUnit.SECONDS);
        URI url = storage.directUrl(file.getStorageKey(), properties.linkTtl())
                .orElseGet(() -> UriComponentsBuilder.fromPath("/api/files/{id}/content")
                        .queryParam("expires", expires.getEpochSecond())
                        .queryParam("download", download)
                        .queryParam("signature", signer.sign(mediaId, expires, download))
                        .buildAndExpand(mediaId)
                        .toUri());
        return new Link(url.toString(), expires);
    }

    /** Файл по подписанной ссылке; токен не нужен, проверяются подпись и срок. */
    @Transactional(readOnly = true)
    public StoredFile fileByLink(UUID mediaId, long expiresEpoch, boolean download, String signature) {
        Instant expires = Instant.ofEpochSecond(expiresEpoch);
        if (!signer.isValid(mediaId, expires, download, signature)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "LINK_INVALID", "Ссылка недействительна");
        }
        if (!expires.isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "LINK_EXPIRED", "Срок действия ссылки истёк");
        }
        return files.findById(mediaId)
                .filter(StoredFile::isComplete)
                .orElseThrow(() -> ApiException.notFound("Файл не найден"));
    }

    public InputStream open(StoredFile file, long offset, long length) throws IOException {
        return storage.openRange(file.getStorageKey(), offset, length);
    }

    /** Удаление по событию media.deleted. Повторное событие ничего не ломает. */
    @Transactional
    public void delete(UUID mediaId) throws IOException {
        String key = files.findById(mediaId).map(StoredFile::getStorageKey).orElse(UploadService.keyFor(mediaId));
        storage.delete(key);
        files.deleteById(mediaId);
        log.info("Файл {} удалён из хранилища", mediaId);
    }

    public record Link(String url, Instant expiresAt) {
    }
}
