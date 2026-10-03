package io.mediagrid.media.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.mediagrid.media.category.CategoryService;
import io.mediagrid.media.category.CategoryService.CategoryResponse;
import io.mediagrid.media.media.MediaDtos.CreateMediaRequest;
import io.mediagrid.media.media.MediaDtos.MediaResponse;
import io.mediagrid.media.media.MediaDtos.PageResponse;
import io.mediagrid.media.media.MediaDtos.SearchFilter;
import io.mediagrid.media.media.MediaDtos.UpdateMediaRequest;
import io.mediagrid.media.media.MediaKind;
import io.mediagrid.media.media.MediaService;
import io.mediagrid.media.media.MediaStatus;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/media")
public class MediaController {

    private static final int MAX_PAGE_SIZE = 100;

    /** По каким полям разрешена сортировка: имя в запросе → поле записи. */
    private static final Map<String, String> SORT_FIELDS = Map.of(
            "createdAt", "createdAt",
            "title", "title",
            "size", "sizeBytes");

    private final MediaService media;
    private final CategoryService categories;

    public MediaController(MediaService media, CategoryService categories) {
        this.media = media;
        this.categories = categories;
    }

    /** Шаг 1 загрузки: запись в каталоге со статусом PENDING_UPLOAD; её id — номер для загрузки файла. */
    @PostMapping
    public ResponseEntity<MediaResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                @Valid @RequestBody CreateMediaRequest request) {
        MediaResponse created = media.create(CurrentUser.of(jwt), request);
        return ResponseEntity.created(URI.create("/api/media/" + created.id())).body(created);
    }

    @GetMapping
    public PageResponse<MediaResponse> search(@AuthenticationPrincipal Jwt jwt,
                                              @RequestParam(required = false) String q,
                                              @RequestParam(required = false) MediaKind type,
                                              @RequestParam(required = false) MediaStatus status,
                                              @RequestParam(required = false) UUID categoryId,
                                              @RequestParam(name = "tag", required = false) List<String> tags,
                                              @RequestParam(defaultValue = "false") boolean mine,
                                              @RequestParam(required = false) UUID ownerId,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(defaultValue = "createdAt") String sort,
                                              @RequestParam(defaultValue = "desc") String direction) {
        String field = SORT_FIELDS.get(sort);
        if (field == null) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Сортировка возможна по полям " + SORT_FIELDS.keySet());
        }
        Sort order = Sort.by("asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC, field)
                .and(Sort.by("id"));
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), order);
        SearchFilter filter = new SearchFilter(q, type, status, categoryId, tags, mine, ownerId);
        Page<MediaResponse> result = media.search(CurrentUser.of(jwt), filter, pageable);
        return new PageResponse<>(result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements());
    }

    @GetMapping("/{id}")
    public MediaResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return media.get(CurrentUser.of(jwt), id);
    }

    @PatchMapping("/{id}")
    public MediaResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                @Valid @RequestBody UpdateMediaRequest request) {
        return media.update(CurrentUser.of(jwt), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        media.delete(CurrentUser.of(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/categories")
    public List<CategoryResponse> categories() {
        return categories.list().stream().map(CategoryResponse::of).toList();
    }
}
