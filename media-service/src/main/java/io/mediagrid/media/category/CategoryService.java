package io.mediagrid.media.category;

import java.util.List;
import java.util.UUID;

import io.mediagrid.support.web.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Категории — общий для всех справочник, ведёт его администратор. Файл относится не более чем к одной. */
@Service
public class CategoryService {

    private final CategoryRepository categories;

    public CategoryService(CategoryRepository categories) {
        this.categories = categories;
    }

    @Transactional(readOnly = true)
    public List<Category> list() {
        return categories.findAll(Sort.by("name"));
    }

    @Transactional(readOnly = true)
    public Category get(UUID id) {
        return categories.findById(id).orElseThrow(() -> ApiException.notFound("Категория не найдена"));
    }

    @Transactional
    public Category create(CategoryRequest request) {
        String name = request.name().trim();
        requireUniqueName(name, null);
        return categories.save(new Category(name, trimToNull(request.description())));
    }

    @Transactional
    public Category update(UUID id, CategoryRequest request) {
        Category category = get(id);
        String name = request.name().trim();
        requireUniqueName(name, id);
        category.setName(name);
        category.setDescription(trimToNull(request.description()));
        return category;
    }

    /** Файлы удалённой категории остаются без категории. */
    @Transactional
    public void delete(UUID id) {
        categories.delete(get(id));
    }

    private void requireUniqueName(String name, UUID exceptId) {
        if (categories.existsByNameIgnoreCaseExcept(name, exceptId)) {
            throw ApiException.conflict("CATEGORY_EXISTS", "Категория с таким названием уже есть");
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record CategoryRequest(@NotBlank @Size(max = 100) String name, @Size(max = 500) String description) {
    }

    public record CategoryResponse(UUID id, String name, String description) {

        public static CategoryResponse of(Category category) {
            return new CategoryResponse(category.getId(), category.getName(), category.getDescription());
        }
    }
}
