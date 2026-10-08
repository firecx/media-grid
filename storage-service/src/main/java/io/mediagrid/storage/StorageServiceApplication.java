package io.mediagrid.storage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Служба загрузки и хранения: приём файлов, хранение, ссылки на скачивание и воспроизведение. */
@SpringBootApplication
@ConfigurationPropertiesScan
// Досылка событий о полученных файлах (StorageEvents)
@EnableScheduling
public class StorageServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StorageServiceApplication.class, args);
    }
}
