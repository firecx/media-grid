package io.mediagrid.storage.upload;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.mediagrid.common.events.FileUploadedEvent;
import io.mediagrid.storage.file.StoredFile;
import io.mediagrid.storage.file.StoredFileRepository;
import io.mediagrid.storage.media.MediaClient;
import io.mediagrid.storage.media.MediaInfo;
import io.mediagrid.storage.provider.StorageProvider;
import io.mediagrid.storage.provider.StorageProvider.TooMuchDataException;
import io.mediagrid.support.security.CurrentUser;
import io.mediagrid.support.web.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Приём файла потоком, целиком или частями с докачкой (ТЗ, п. 4.1.3, 4.1.4).
 * Часть должна начинаться ровно там, где закончились полученные данные; после обрыва связи клиент
 * узнаёт это место запросом HEAD и продолжает. Загрузка без Content-Range начинает файл заново.
 * Транзакции короткие: поток пишется на диск вне транзакции, чтобы долгая загрузка не держала базу.
 */
@Service
public class UploadService {

    private final StoredFileRepository files;
    private final StorageProvider storage;
    private final MediaClient media;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    /** Файлы, загрузка которых идёт прямо сейчас: вторая одновременная загрузка того же файла — 409. */
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    public UploadService(StoredFileRepository files, StorageProvider storage, MediaClient media,
                         TransactionTemplate tx, ApplicationEventPublisher events) {
        this.files = files;
        this.storage = storage;
        this.media = media;
        this.tx = tx;
        this.events = events;
    }

    /**
     * @param contentLength длина тела запроса из Content-Length; -1, если неизвестна (передача частями)
     */
    public UploadState upload(UUID mediaId, CurrentUser user, String authorization, ContentRange range,
                              long contentLength, InputStream body) throws IOException {
        MediaInfo info = media.get(mediaId, authorization);
        requireOwner(info, user);
        if (!active.add(mediaId)) {
            throw ApiException.conflict("UPLOAD_IN_PROGRESS", "Этот файл уже загружается");
        }
        try {
            StoredFile file = tx.execute(status -> files.findById(mediaId)
                    .orElseGet(() -> files.save(new StoredFile(mediaId, info.ownerId(), info.contentType(),
                            info.originalFilename(), info.sizeBytes(), storage.name(), keyFor(mediaId)))));
            if (file.isComplete()) {
                // Файл уже получен целиком. Если каталог об этом не узнал (событие потерялось) — повторяем
                if (info.awaitingUpload()) {
                    events.publishEvent(uploadedEvent(file));
                }
                return UploadState.of(file);
            }
            if (!info.awaitingUpload()) {
                throw ApiException.conflict("ALREADY_UPLOADED", "Файл для этой записи уже загружен");
            }
            return receive(file, range, contentLength, body);
        } finally {
            active.remove(mediaId);
        }
    }

    /** Сколько байт уже получено — для докачки. Узнать может только владелец. */
    public UploadState status(UUID mediaId, CurrentUser user, String authorization) {
        MediaInfo info = media.get(mediaId, authorization);
        requireOwner(info, user);
        return files.findById(mediaId)
                .map(UploadState::of)
                .orElse(new UploadState(mediaId, 0, info.sizeBytes(), false));
    }

    private UploadState receive(StoredFile file, ContentRange range, long contentLength, InputStream body)
            throws IOException {
        String key = file.getStorageKey();
        long offset = storage.size(key);
        long maxBytes;
        if (range == null) {
            if (offset > 0) {
                storage.delete(key);
            }
            maxBytes = file.getExpectedSize();
        } else {
            if (range.total() != file.getExpectedSize()) {
                throw ApiException.badRequest("SIZE_MISMATCH",
                        "Размер файла в Content-Range не совпадает с заявленным: " + file.getExpectedSize());
            }
            if (range.start() != offset) {
                throw ApiException.conflict("UPLOAD_OFFSET_MISMATCH",
                        "Продолжать загрузку нужно с байта " + offset);
            }
            maxBytes = range.length();
        }
        if (contentLength >= 0 && contentLength != maxBytes) {
            throw ApiException.badRequest("SIZE_MISMATCH",
                    "Длина тела запроса " + contentLength + " не совпадает с ожидаемой " + maxBytes);
        }
        long before = storage.size(key);
        try {
            storage.append(key, body, maxBytes);
        } catch (TooMuchDataException e) {
            // Данных больше, чем заявлено, — эта часть недостоверна и откатывается целиком
            storage.truncate(key, before);
            throw ApiException.badRequest("BODY_TOO_LARGE", "В запросе больше данных, чем заявлено");
        } finally {
            // Полученное фиксируется и при обрыве связи: с этого места загрузку можно продолжить
            record(file.getMediaId(), storage.size(key));
        }
        return files.findById(file.getMediaId()).map(UploadState::of)
                .orElseThrow(() -> ApiException.notFound("Файл не найден"));
    }

    private void record(UUID mediaId, long received) {
        tx.executeWithoutResult(status -> {
            StoredFile current = files.findById(mediaId).orElse(null);
            if (current == null) {
                // Запись удалили, пока шла загрузка: событие удаления уже обработано, данные не нужны
                deleteQuietly(keyFor(mediaId));
                return;
            }
            boolean wasComplete = current.isComplete();
            current.received(received);
            if (current.isComplete() && !wasComplete) {
                // Уйдёт в шину после фиксации транзакции
                events.publishEvent(uploadedEvent(current));
            }
        });
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (IOException ignored) {
            // останется лишний файл, но не запись без файла
        }
    }

    private static void requireOwner(MediaInfo info, CurrentUser user) {
        if (!info.ownerId().equals(user.id())) {
            throw ApiException.forbidden("FORBIDDEN", "Загружать файл может только владелец записи");
        }
    }

    private static FileUploadedEvent uploadedEvent(StoredFile file) {
        return new FileUploadedEvent(file.getMediaId(), file.getOwnerId(), file.getContentType(),
                file.getReceivedSize());
    }

    /** Ключ исходного файла; подкаталог по первым знакам номера, чтобы в одном каталоге не копились тысячи файлов. */
    public static String keyFor(UUID mediaId) {
        String id = mediaId.toString();
        return "originals/" + id.substring(0, 2) + "/" + id;
    }

    public record UploadState(UUID mediaId, long receivedBytes, long expectedBytes, boolean complete) {

        static UploadState of(StoredFile file) {
            return new UploadState(file.getMediaId(), file.getReceivedSize(), file.getExpectedSize(),
                    file.isComplete());
        }
    }
}
