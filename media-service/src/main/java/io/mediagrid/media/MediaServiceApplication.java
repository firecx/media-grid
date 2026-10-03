package io.mediagrid.media;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Служба медиаданных: каталог файлов, категории, теги, поиск, статус обработки. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MediaServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MediaServiceApplication.class, args);
    }
}
