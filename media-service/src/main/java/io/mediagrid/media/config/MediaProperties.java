package io.mediagrid.media.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Настройки службы медиаданных (раздел mediagrid.media в config/media-service.yml).
 *
 * @param maxFileSize      наибольший размер одного файла
 * @param pendingUploadTtl сколько запись может ждать загрузки файла, прежде чем будет удалена
 */
@ConfigurationProperties("mediagrid.media")
public record MediaProperties(
        @DefaultValue("20GB") DataSize maxFileSize,
        @DefaultValue("24h") Duration pendingUploadTtl) {
}
