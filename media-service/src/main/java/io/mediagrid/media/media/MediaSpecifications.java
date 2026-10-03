package io.mediagrid.media.media;

import java.util.Locale;
import java.util.UUID;

import io.mediagrid.media.tag.Tag;
import io.mediagrid.support.security.CurrentUser;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

/** Условия поиска по каталогу. */
final class MediaSpecifications {

    private MediaSpecifications() {
    }

    /**
     * Что пользователь вправе видеть: администратор — всё; остальные — свои файлы в любом статусе
     * и общедоступные чужие, у которых файл уже загружен.
     */
    static Specification<MediaFile> visibleTo(CurrentUser user) {
        if (user.admin()) {
            return (root, query, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> cb.or(
                cb.equal(root.get("ownerId"), user.id()),
                cb.and(
                        cb.equal(root.get("visibility"), Visibility.PUBLIC),
                        cb.notEqual(root.get("status"), MediaStatus.PENDING_UPLOAD)));
    }

    /** Вхождение строки в название или имя исходного файла, без учёта регистра. */
    static Specification<MediaFile> textContains(String text) {
        String pattern = "%" + escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern, '\\'),
                cb.like(cb.lower(root.get("originalFilename")), pattern, '\\'));
    }

    static Specification<MediaFile> kind(MediaKind kind) {
        return (root, query, cb) -> cb.equal(root.get("mediaKind"), kind);
    }

    static Specification<MediaFile> status(MediaStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    static Specification<MediaFile> owner(UUID ownerId) {
        return (root, query, cb) -> cb.equal(root.get("ownerId"), ownerId);
    }

    static Specification<MediaFile> category(UUID categoryId) {
        return (root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId);
    }

    /** Файл помечен тегом (подзапрос, чтобы условия по нескольким тегам складывались через «и»). */
    static Specification<MediaFile> hasTag(String tagName) {
        return (root, query, cb) -> {
            Subquery<UUID> sub = query.subquery(UUID.class);
            var media = sub.from(MediaFile.class);
            Join<MediaFile, Tag> tag = media.join("tags");
            sub.select(media.get("id"))
                    .where(cb.equal(media.get("id"), root.get("id")), cb.equal(tag.get("name"), tagName));
            return cb.exists(sub);
        };
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
