package io.mediagrid.storage.config;

import java.nio.file.Path;
import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки службы хранения (раздел mediagrid.storage в config/storage-service.yml).
 *
 * @param root       каталог локального хранилища; наружу не публикуется
 * @param linkTtl    срок действия ссылки на скачивание и воспроизведение
 * @param linkSecret ключ подписи ссылок, не короче 32 символов; без него служба не запустится
 */
@Validated
@ConfigurationProperties("mediagrid.storage")
public record StorageProperties(
        @NotNull @DefaultValue("./data/storage") Path root,
        @NotNull @DefaultValue("4h") Duration linkTtl,
        @NotBlank @Size(min = 32) String linkSecret) {
}
