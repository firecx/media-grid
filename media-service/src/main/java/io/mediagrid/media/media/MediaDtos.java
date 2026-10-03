package io.mediagrid.media.media;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import io.mediagrid.media.category.CategoryService.CategoryResponse;
import io.mediagrid.media.tag.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Форматы запросов и ответов для работы с каталогом. */
public final class MediaDtos {

    private MediaDtos() {
    }

    public record MediaResponse(UUID id, UUID ownerId, String title, String originalFilename, String contentType,
                                MediaKind mediaKind, long sizeBytes, MediaStatus status, Visibility visibility,
                                CategoryResponse category, List<String> tags, boolean hasPreview,
                                String processingError, Instant createdAt, Instant updatedAt, Instant uploadedAt) {

        public static MediaResponse of(MediaFile media) {
            return new MediaResponse(media.getId(), media.getOwnerId(), media.getTitle(),
                    media.getOriginalFilename(), media.getContentType(), media.getMediaKind(),
                    media.getSizeBytes(), media.getStatus(), media.getVisibility(),
                    media.getCategory() == null ? null : CategoryResponse.of(media.getCategory()),
                    media.getTags().stream().map(Tag::getName).sorted(Comparator.naturalOrder()).toList(),
                    media.hasPreview(), media.getProcessingError(), media.getCreatedAt(), media.getUpdatedAt(),
                    media.getUploadedAt());
        }
    }

    /** Создание записи перед загрузкой файла. Размер и тип потом сверяет служба хранения. */
    public record CreateMediaRequest(
            @NotBlank @Size(max = 255) String title,
            @NotBlank @Size(max = 255) String originalFilename,
            @NotBlank @Size(max = 127) String contentType,
            @Positive long sizeBytes,
            UUID categoryId,
            List<String> tags,
            Visibility visibility) {
    }

    /**
     * Изменение записи; незаполненные поля не меняются. tags, если указаны, заменяют весь список.
     * Чтобы убрать категорию, передаётся removeCategory: true.
     */
    public record UpdateMediaRequest(
            @Size(min = 1, max = 255) String title,
            UUID categoryId,
            Boolean removeCategory,
            List<String> tags,
            Visibility visibility) {
    }

    /** Условия поиска; все необязательны и складываются через «и». */
    public record SearchFilter(String q, MediaKind type, MediaStatus status, UUID categoryId, List<String> tags,
                               boolean mine, UUID ownerId) {
    }

    public record PageResponse<T>(List<T> items, int page, int size, long total) {
    }
}
