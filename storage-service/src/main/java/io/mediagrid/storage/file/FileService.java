package io.mediagrid.storage.file;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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

/** Ссылки на файл и его производные, выдача по ссылке и удаление. */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final StoredFileRepository files;
    private final FileVariantRepository variants;
    private final StorageProvider storage;
    private final MediaClient media;
    private final LinkSigner signer;
    private final StorageProperties properties;

    public FileService(StoredFileRepository files, FileVariantRepository variants, StorageProvider storage,
                       MediaClient media, LinkSigner signer, StorageProperties properties) {
        this.files = files;
        this.variants = variants;
        this.storage = storage;
        this.media = media;
        this.signer = signer;
        this.properties = properties;
    }

    /**
     * Ссылка с ограниченным сроком действия (ТЗ, п. 4.1.5). Право на неё проверяет служба медиаданных:
     * ссылку получит только тот, кому запись видна. Для воспроизведения (playback), если служба обработки
     * не делала отдельной версии (браузер воспроизводит исходный файл), выдаётся ссылка на исходный файл.
     */
    @Transactional(readOnly = true)
    public Link createLink(UUID mediaId, String authorization, boolean download, VariantKind requested) {
        media.get(mediaId, authorization);
        StoredFile file = files.findById(mediaId)
                .filter(StoredFile::isComplete)
                .orElseThrow(() -> ApiException.conflict("NOT_UPLOADED", "Файл ещё не загружен"));
        VariantKind kind = requested;
        String key = file.getStorageKey();
        if (requested != VariantKind.ORIGINAL) {
            FileVariant variant = variants.findById(new FileVariant.Key(mediaId, requested)).orElse(null);
            if (variant != null) {
                key = variant.getStorageKey();
            } else if (requested == VariantKind.PLAYBACK) {
                kind = VariantKind.ORIGINAL;
            } else {
                throw ApiException.notFound("У файла нет такого вида: " + requested.pathName());
            }
        }
        Instant expires = Instant.now().plus(properties.linkTtl()).truncatedTo(ChronoUnit.SECONDS);
        VariantKind linked = kind;
        URI url = storage.directUrl(key, properties.linkTtl())
                .orElseGet(() -> UriComponentsBuilder.fromPath("/api/files/{id}/content")
                        .queryParam("variant", linked.pathName())
                        .queryParam("expires", expires.getEpochSecond())
                        .queryParam("download", download)
                        .queryParam("signature", signer.sign(mediaId, linked, expires, download))
                        .buildAndExpand(mediaId)
                        .toUri());
        return new Link(url.toString(), expires, linked.pathName());
    }

    /** Содержимое по подписанной ссылке; токен не нужен, проверяются подпись и срок. */
    @Transactional(readOnly = true)
    public Content contentByLink(UUID mediaId, VariantKind kind, long expiresEpoch, boolean download,
                                 String signature) {
        Instant expires = Instant.ofEpochSecond(expiresEpoch);
        if (!signer.isValid(mediaId, kind, expires, download, signature)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "LINK_INVALID", "Ссылка недействительна");
        }
        if (!expires.isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "LINK_EXPIRED", "Срок действия ссылки истёк");
        }
        StoredFile file = files.findById(mediaId)
                .filter(StoredFile::isComplete)
                .orElseThrow(() -> ApiException.notFound("Файл не найден"));
        if (kind == VariantKind.ORIGINAL) {
            return new Content(file.getStorageKey(), file.getContentType(), file.getReceivedSize(),
                    file.getOriginalFilename());
        }
        FileVariant variant = variants.findById(new FileVariant.Key(mediaId, kind))
                .orElseThrow(() -> ApiException.notFound("Файл не найден"));
        return new Content(variant.getStorageKey(), variant.getContentType(), variant.getSizeBytes(),
                variantFilename(file.getOriginalFilename(), kind, variant.getContentType()));
    }

    public InputStream open(Content content, long offset, long length) throws IOException {
        return storage.openRange(content.storageKey(), offset, length);
    }

    /** Удаление по событию media.deleted вместе с производными. Повторное событие ничего не ломает. */
    @Transactional
    public void delete(UUID mediaId) throws IOException {
        List<FileVariant> derived = variants.findByMediaId(mediaId);
        for (FileVariant variant : derived) {
            storage.delete(variant.getStorageKey());
        }
        variants.deleteAll(derived);
        String key = files.findById(mediaId).map(StoredFile::getStorageKey).orElse(UploadService.keyFor(mediaId));
        storage.delete(key);
        files.deleteById(mediaId);
        log.info("Файл {} удалён из хранилища вместе с производными ({})", mediaId, derived.size());
    }

    /** «Отпуск.mov» → «Отпуск-preview.jpg»: имя для сохранения производного файла. */
    static String variantFilename(String original, VariantKind kind, String contentType) {
        int dot = original.lastIndexOf('.');
        String base = dot > 0 ? original.substring(0, dot) : original;
        String extension = switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "video/mp4" -> ".mp4";
            case "audio/mp4" -> ".m4a";
            default -> "";
        };
        return kind == VariantKind.PLAYBACK ? base + extension : base + "-" + kind.pathName() + extension;
    }

    /** variant — что именно выдаётся по ссылке; для playback это может оказаться original. */
    public record Link(String url, Instant expiresAt, String variant) {
    }

    /** Что отдавать: где лежит, какого типа, сколько байт и под каким именем. */
    public record Content(String storageKey, String contentType, long size, String filename) {
    }
}
