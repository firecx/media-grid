package io.mediagrid.storage.file;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import io.mediagrid.storage.provider.StorageProvider;
import io.mediagrid.storage.provider.StorageProvider.TooMuchDataException;
import io.mediagrid.support.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Внутренний обмен со службой обработки: она читает исходный файл и сохраняет производные.
 * Права пользователя здесь не проверяются — путь /internal/** доступен только токену службы.
 */
@Service
public class VariantService {

    private static final Logger log = LoggerFactory.getLogger(VariantService.class);

    private final StoredFileRepository files;
    private final FileVariantRepository variants;
    private final StorageProvider storage;
    private final TransactionTemplate tx;

    public VariantService(StoredFileRepository files, FileVariantRepository variants, StorageProvider storage,
                          TransactionTemplate tx) {
        this.files = files;
        this.variants = variants;
        this.storage = storage;
        this.tx = tx;
    }

    /** Полностью загруженный исходный файл. 404 — файла нет (запись удалена) или он ещё не загружен. */
    public StoredFile original(UUID mediaId) {
        return files.findById(mediaId)
                .filter(StoredFile::isComplete)
                .orElseThrow(() -> ApiException.notFound("Файл не найден"));
    }

    public InputStream openOriginal(StoredFile file) throws IOException {
        return storage.openRange(file.getStorageKey(), 0, file.getReceivedSize());
    }

    /**
     * Сохраняет производный файл. Каждая версия пишется под новым ключом, и только после полной записи
     * запись в базе переключается на неё: оборванная передача не портит прежнюю версию.
     */
    public FileVariant store(UUID mediaId, VariantKind kind, String contentType, long contentLength, InputStream body)
            throws IOException {
        if (kind == VariantKind.ORIGINAL) {
            throw ApiException.badRequest("INVALID_VARIANT", "Исходный файл загружается пользователем, а не службой");
        }
        if (contentLength < 0) {
            throw new ApiException(HttpStatus.LENGTH_REQUIRED, "LENGTH_REQUIRED", "Нужен заголовок Content-Length");
        }
        String type = mediaType(contentType);
        original(mediaId);
        String key = keyFor(mediaId, kind);
        long written;
        try {
            written = storage.append(key, body, contentLength);
        } catch (TooMuchDataException e) {
            storage.delete(key);
            throw ApiException.badRequest("BODY_TOO_LARGE", "В запросе больше данных, чем заявлено");
        } catch (IOException e) {
            storage.delete(key);
            throw e;
        }
        if (written != contentLength) {
            storage.delete(key);
            throw ApiException.badRequest("SIZE_MISMATCH", "Получено " + written + " байт из " + contentLength);
        }
        String previous = tx.execute(status -> {
            if (files.findById(mediaId).isEmpty()) {
                return key; // запись удалили, пока шла передача: новая версия не нужна
            }
            String old = variants.findById(new FileVariant.Key(mediaId, kind)).map(FileVariant::getStorageKey)
                    .orElse(null);
            variants.save(new FileVariant(mediaId, kind, type, written, key));
            return old;
        });
        if (previous != null) {
            storage.delete(previous);
        }
        if (key.equals(previous)) {
            throw ApiException.notFound("Файл не найден");
        }
        log.info("Сохранён производный файл {} для {}: {} байт", kind, mediaId, written);
        return variants.findById(new FileVariant.Key(mediaId, kind)).orElseThrow();
    }

    /** Только тип без параметров (charset и т. п. у двоичных файлов не нужны). */
    private static String mediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw ApiException.badRequest("CONTENT_TYPE_REQUIRED", "Нужен заголовок Content-Type");
        }
        try {
            MediaType parsed = MediaType.parseMediaType(contentType);
            return parsed.getType() + "/" + parsed.getSubtype();
        } catch (InvalidMediaTypeException e) {
            throw ApiException.badRequest("CONTENT_TYPE_REQUIRED", "Неверный Content-Type: " + contentType);
        }
    }

    /** Ключ производного файла: рядом с другими производными того же файла, у каждой версии свой. */
    static String keyFor(UUID mediaId, VariantKind kind) {
        String id = mediaId.toString();
        return "variants/" + id.substring(0, 2) + "/" + id + "/" + kind.pathName() + "-" + UUID.randomUUID();
    }
}
