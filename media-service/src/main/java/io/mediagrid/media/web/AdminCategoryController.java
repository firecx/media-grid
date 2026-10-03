package io.mediagrid.media.web;

import java.net.URI;
import java.util.UUID;

import io.mediagrid.media.category.Category;
import io.mediagrid.media.category.CategoryService;
import io.mediagrid.media.category.CategoryService.CategoryRequest;
import io.mediagrid.media.category.CategoryService.CategoryResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Справочник категорий — только для роли ADMIN (проверяется в SecurityConfig по пути). */
@RestController
@RequestMapping("/api/media/admin/categories")
public class AdminCategoryController {

    private final CategoryService categories;

    public AdminCategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
        Category category = categories.create(request);
        return ResponseEntity.created(URI.create("/api/media/categories/" + category.getId()))
                .body(CategoryResponse.of(category));
    }

    @PutMapping("/{id}")
    public CategoryResponse update(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
        return CategoryResponse.of(categories.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        categories.delete(id);
        return ResponseEntity.noContent().build();
    }
}
