package io.mediagrid.media.media;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.mediagrid.common.events.FileUploadedEvent;
import io.mediagrid.common.events.MediaDeletedEvent;
import io.mediagrid.common.events.ProcessingCompletedEvent;
import io.mediagrid.media.category.CategoryService;
import io.mediagrid.media.config.MediaProperties;
import io.mediagrid.media.media.MediaDtos.CreateMediaRequest;
import io.mediagrid.media.media.MediaDtos.MediaResponse;
import io.mediagrid.media.media.MediaDtos.SearchFilter;
import io.mediagrid.media.media.MediaDtos.UpdateMediaRequest;
import io.mediagrid.media.tag.TagService;
import io.mediagrid.support.web.ApiException;
import io.mediagrid.support.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private final MediaFileRepository media;
    private final CategoryService categories;
    private final TagService tags;
    private final ApplicationEventPublisher events;
    private final MediaProperties properties;

    public MediaService(MediaFileRepository media, CategoryService categories, TagService tags,
                        ApplicationEventPublisher events, MediaProperties properties) {
        this.media = media;
        this.categories = categories;
        this.tags = tags;
        this.events = events;
        this.properties = properties;
    }

    @Transactional
    public MediaResponse create(CurrentUser user, CreateMediaRequest request) {
        String contentType = MediaKind.normalizeContentType(request.contentType());
        MediaKind kind = MediaKind.fromContentType(contentType).orElseThrow(() -> ApiException.badRequest(
                "UNSUPPORTED_MEDIA_TYPE", "Поддерживаются только изображения, видео и аудио"));
        if (request.sizeBytes() > properties.maxFileSize().toBytes()) {
            throw ApiException.badRequest("FILE_TOO_LARGE",
                    "Файл больше допустимого размера " + properties.maxFileSize().toMegabytes() + " МБ");
        }
        MediaFile file = new MediaFile(user.id(), request.title().trim(), request.originalFilename().trim(),
                contentType, kind, request.sizeBytes(),
                request.visibility() == null ? Visibility.PRIVATE : request.visibility());
        if (request.categoryId() != null) {
            file.setCategory(categories.get(request.categoryId()));
        }
        if (request.tags() != null) {
            file.setTags(tags.resolve(request.tags()));
        }
        return MediaResponse.of(media.save(file));
    }

    @Transactional(readOnly = true)
    public MediaResponse get(CurrentUser user, UUID id) {
        return MediaResponse.of(find(user, id));
    }

    /** Чужой недоступный файл выглядит как несуществующий: так не раскрывается само его наличие. */
    private MediaFile find(CurrentUser user, UUID id) {
        return media.findOne(MediaSpecifications.visibleTo(user).and((root, query, cb) -> cb.equal(root.get("id"), id)))
                .orElseThrow(() -> ApiException.notFound("Файл не найден"));
    }

    @Transactional(readOnly = true)
    public Page<MediaResponse> search(CurrentUser user, SearchFilter filter, Pageable pageable) {
        List<Specification<MediaFile>> conditions = new ArrayList<>();
        conditions.add(MediaSpecifications.visibleTo(user));
        if (filter.q() != null && !filter.q().isBlank()) {
            conditions.add(MediaSpecifications.textContains(filter.q()));
        }
        if (filter.type() != null) {
            conditions.add(MediaSpecifications.kind(filter.type()));
        }
        if (filter.status() != null) {
            conditions.add(MediaSpecifications.status(filter.status()));
        }
        if (filter.categoryId() != null) {
            conditions.add(MediaSpecifications.category(filter.categoryId()));
        }
        if (filter.tags() != null) {
            TagService.normalize(filter.tags()).forEach(tag -> conditions.add(MediaSpecifications.hasTag(tag)));
        }
        if (filter.mine()) {
            conditions.add(MediaSpecifications.owner(user.id()));
        } else if (filter.ownerId() != null) {
            conditions.add(MediaSpecifications.owner(filter.ownerId()));
        }
        // Ответ собирается внутри транзакции: теги и категории подгружаются пачками
        return media.findAll(Specification.allOf(conditions), pageable).map(MediaResponse::of);
    }

    @Transactional
    public MediaResponse update(CurrentUser user, UUID id, UpdateMediaRequest request) {
        MediaFile file = getForChange(user, id);
        if (request.title() != null) {
            if (request.title().isBlank()) {
                throw ApiException.badRequest("VALIDATION_ERROR", "Название не может быть пустым");
            }
            file.setTitle(request.title().trim());
        }
        if (Boolean.TRUE.equals(request.removeCategory())) {
            file.setCategory(null);
        } else if (request.categoryId() != null) {
            file.setCategory(categories.get(request.categoryId()));
        }
        if (request.tags() != null) {
            file.setTags(tags.resolve(request.tags()));
        }
        if (request.visibility() != null) {
            file.setVisibility(request.visibility());
        }
        return MediaResponse.of(file);
    }

    /** Запись удаляется сразу; файл из хранилища удаляет служба хранения по событию после фиксации транзакции. */
    @Transactional
    public void delete(CurrentUser user, UUID id) {
        MediaFile file = getForChange(user, id);
        media.delete(file);
        events.publishEvent(new MediaDeletedEvent(file.getId()));
    }

    /** Служба хранения сохранила файл. Повторное событие ничего не меняет. */
    @Transactional
    public void onFileUploaded(FileUploadedEvent event) {
        MediaFile file = media.findById(event.mediaId()).orElse(null);
        if (file == null) {
            // Запись удалили, пока шла загрузка: файл в хранилище больше не нужен
            log.warn("Загружен файл {}, записи о котором нет: хранилище получит команду на удаление",
                    event.mediaId());
            events.publishEvent(new MediaDeletedEvent(event.mediaId()));
            return;
        }
        if (file.getStatus() == MediaStatus.PENDING_UPLOAD) {
            file.markUploaded();
        }
    }

    /**
     * Служба обработки закончила работу. Если записи уже нет — событие просто устарело. События идут
     * по разным очередям, поэтому это может прийти раньше file.uploaded: обработанный файл точно загружен.
     */
    @Transactional
    public void onProcessingCompleted(ProcessingCompletedEvent event) {
        media.findById(event.mediaId()).ifPresent(file -> {
            if (file.getStatus() == MediaStatus.PENDING_UPLOAD) {
                file.markUploaded();
            }
            file.markProcessed(event.success(), event.hasPreview(), truncate(event.errorMessage(), 1000));
        });
    }

    /**
     * Удаляет записи, файл для которых так и не был загружен (ТЗ, п. 4.1.9): в каталоге не остаётся
     * записей о файлах, которых нет в хранилище. Хранилище получает команду удалить недокачанные части.
     */
    @Scheduled(fixedDelayString = "${mediagrid.media.cleanup-interval:PT1H}", initialDelayString = "PT1M")
    @Transactional
    public int deleteStalePendingUploads() {
        Instant before = Instant.now().minus(properties.pendingUploadTtl());
        List<MediaFile> stale = media.findTop100ByStatusAndCreatedAtBefore(MediaStatus.PENDING_UPLOAD, before);
        for (MediaFile file : stale) {
            media.delete(file);
            events.publishEvent(new MediaDeletedEvent(file.getId()));
        }
        if (!stale.isEmpty()) {
            log.info("Удалено записей без загруженного файла: {}", stale.size());
        }
        return stale.size();
    }

    /** Менять и удалять файл могут владелец и администратор; остальным — 404 или 403. */
    private MediaFile getForChange(CurrentUser user, UUID id) {
        MediaFile file = find(user, id);
        if (!user.admin() && !file.isOwnedBy(user.id())) {
            throw ApiException.forbidden("FORBIDDEN", "Изменять файл может только владелец");
        }
        return file;
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
