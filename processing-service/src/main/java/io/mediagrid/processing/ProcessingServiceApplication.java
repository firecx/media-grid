package io.mediagrid.processing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Служба обработки: очередь задач, транскодирование, миниатюры и превью. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ProcessingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcessingServiceApplication.class, args);
    }
}
