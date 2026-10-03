package io.mediagrid.storage.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import io.mediagrid.storage.file.FileVariant;
import io.mediagrid.storage.file.StoredFile;
import io.mediagrid.storage.file.VariantKind;
import io.mediagrid.storage.file.VariantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Внутренний интерфейс для службы обработки (только токен службы, шлюз путь /internal/** наружу
 * не пропускает). Файлы передаются потоком в обе стороны, без накопления в памяти.
 */
@RestController
@RequestMapping("/internal/files")
public class InternalFileController {

    private final VariantService variants;

    public InternalFileController(VariantService variants) {
        this.variants = variants;
    }

    /** Исходный файл целиком. */
    @GetMapping("/{mediaId}")
    public void original(@PathVariable UUID mediaId, HttpServletResponse response) throws IOException {
        StoredFile file = variants.original(mediaId);
        response.setContentType(file.getContentType());
        response.setContentLengthLong(file.getReceivedSize());
        try (InputStream in = variants.openOriginal(file); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    /** Производный файл: kind = thumbnail, preview или playback. Новая версия заменяет прежнюю. */
    @PutMapping("/{mediaId}/variants/{kind}")
    public VariantResponse storeVariant(@PathVariable UUID mediaId, @PathVariable String kind,
                                        HttpServletRequest request) throws IOException {
        FileVariant variant = variants.store(mediaId, VariantKind.fromPath(kind),
                request.getHeader(HttpHeaders.CONTENT_TYPE), request.getContentLengthLong(), request.getInputStream());
        return new VariantResponse(variant.getKind().pathName(), variant.getContentType(), variant.getSizeBytes());
    }

    public record VariantResponse(String kind, String contentType, long sizeBytes) {
    }
}
