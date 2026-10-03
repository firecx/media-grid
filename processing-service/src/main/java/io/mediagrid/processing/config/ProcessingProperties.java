package io.mediagrid.processing.config;

import java.nio.file.Path;
import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Настройки службы обработки (раздел mediagrid.processing в config/processing-service.yml). */
@ConfigurationProperties("mediagrid.processing")
@Validated
public record ProcessingProperties(
        @NotNull @DefaultValue("./data/processing") Path workDir,
        @Min(1) @DefaultValue("1") int workers,
        @NotNull @DefaultValue("5s") Duration pollInterval,
        @Min(1) @DefaultValue("5") int maxAttempts,
        @NotNull @DefaultValue("30s") Duration retryDelay,
        @NotNull @DefaultValue("5m") Duration staleAfter,
        @NotNull @DefaultValue("6h") Duration jobTimeout,
        @NotNull @DefaultValue("1h") Duration transferTimeout,
        @NotBlank @DefaultValue("ffmpeg") String ffmpeg,
        @NotBlank @DefaultValue("ffprobe") String ffprobe,
        @Min(16) @DefaultValue("320") int thumbnailSize,
        @Min(16) @DefaultValue("1280") int previewSize,
        @NotBlank String clientSecret) {
}
