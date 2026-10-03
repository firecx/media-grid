package io.mediagrid.storage.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.mediagrid.storage.file.FileService;
import io.mediagrid.storage.file.FileService.Content;
import io.mediagrid.storage.file.FileService.Link;
import io.mediagrid.storage.file.VariantKind;
import io.mediagrid.storage.upload.ContentRange;
import io.mediagrid.storage.upload.UploadService;
import io.mediagrid.storage.upload.UploadService.UploadState;
import io.mediagrid.support.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/files")
public class FileController {

    /** Сколько байт файла уже получено — для докачки. */
    static final String UPLOAD_OFFSET = "Upload-Offset";
    /** Полный размер файла. */
    static final String UPLOAD_LENGTH = "Upload-Length";

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    private final UploadService uploads;
    private final FileService files;

    public FileController(UploadService uploads, FileService files) {
        this.uploads = uploads;
        this.files = files;
    }

    /**
     * Шаг 2 загрузки: тело запроса — сам файл или его часть (Content-Range). Тело читается потоком
     * прямо из соединения и пишется на диск, не накапливаясь в памяти.
     */
    @PutMapping("/{mediaId}")
    public ResponseEntity<UploadState> upload(@PathVariable UUID mediaId, @AuthenticationPrincipal Jwt jwt,
                                              @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                              @RequestHeader(value = HttpHeaders.CONTENT_RANGE, required = false)
                                              String contentRange,
                                              HttpServletRequest request) throws IOException {
        UploadState state = uploads.upload(mediaId, CurrentUser.of(jwt), authorization,
                ContentRange.parse(contentRange), request.getContentLengthLong(), request.getInputStream());
        return ResponseEntity.ok()
                .header(UPLOAD_OFFSET, String.valueOf(state.receivedBytes()))
                .header(UPLOAD_LENGTH, String.valueOf(state.expectedBytes()))
                .body(state);
    }

    /** Сколько уже получено: после обрыва загрузку продолжают с Upload-Offset. */
    @RequestMapping(path = "/{mediaId}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> uploadStatus(@PathVariable UUID mediaId, @AuthenticationPrincipal Jwt jwt,
                                             @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        UploadState state = uploads.status(mediaId, CurrentUser.of(jwt), authorization);
        return ResponseEntity.ok()
                .header(UPLOAD_OFFSET, String.valueOf(state.receivedBytes()))
                .header(UPLOAD_LENGTH, String.valueOf(state.expectedBytes()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    /**
     * Ссылка для просмотра (download=false) или скачивания (download=true) исходного файла или производного:
     * variant = original (по умолчанию), playback, preview, thumbnail.
     */
    @PostMapping("/{mediaId}/links")
    public Link createLink(@PathVariable UUID mediaId,
                           @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                           @RequestBody(required = false) LinkRequest request) {
        LinkRequest options = request != null ? request : new LinkRequest(null, null);
        VariantKind variant = options.variant() == null
                ? VariantKind.ORIGINAL
                : VariantKind.fromPath(options.variant());
        return files.createLink(mediaId, authorization, Boolean.TRUE.equals(options.download()), variant);
    }

    /**
     * Выдача по подписанной ссылке, без токена. Поддерживается частичная загрузка (Range): видео можно
     * перематывать, не скачивая файл целиком. Ответ пишется прямо в соединение, без накопления в памяти.
     */
    @GetMapping("/{mediaId}/content")
    public void content(@PathVariable UUID mediaId,
                        @RequestParam(defaultValue = "original") String variant, @RequestParam long expires,
                        @RequestParam(defaultValue = "false") boolean download, @RequestParam String signature,
                        HttpServletRequest request, HttpServletResponse response) throws IOException {
        Content file = files.contentByLink(mediaId, VariantKind.fromPath(variant), expires, download, signature);
        long size = file.size();

        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setContentType(file.contentType());
        response.setHeader("X-Content-Type-Options", "nosniff");
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(file.filename(), StandardCharsets.UTF_8).build();
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        // Кэшировать можно только в браузере пользователя и не дольше срока ссылки
        long maxAge = Math.max(0, Math.min(Duration.ofHours(1).toSeconds(), expires - Instant.now().getEpochSecond()));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=" + maxAge);

        long start = 0;
        long length = size;
        String rangeHeader = request.getHeader(HttpHeaders.RANGE);
        if (rangeHeader != null) {
            try {
                List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
                // Несколько частей в одном ответе плееры не запрашивают; отдаётся первая
                HttpRange range = ranges.getFirst();
                start = range.getRangeStart(size);
                length = range.getRangeEnd(size) - start + 1;
                // Spring не считает ошибкой начало за концом файла — проверяем сами
                if (start >= size || length <= 0) {
                    throw new IllegalArgumentException("Часть за пределами файла: " + rangeHeader);
                }
            } catch (IllegalArgumentException e) {
                response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
                return;
            }
            response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE,
                    "bytes " + start + "-" + (start + length - 1) + "/" + size);
        }
        response.setContentLengthLong(length);
        if (HttpMethod.HEAD.matches(request.getMethod())) {
            return;
        }
        try (InputStream in = files.open(file, start, length); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        } catch (IOException e) {
            // Обычное дело при перемотке: плеер закрывает соединение и запрашивает другую часть
            log.debug("Выдача файла {} прервана: {}", mediaId, e.getMessage());
        }
    }

    /** Оба поля необязательны: по умолчанию ссылка на просмотр исходного файла. */
    public record LinkRequest(Boolean download, String variant) {
    }
}
