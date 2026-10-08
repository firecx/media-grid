package io.mediagrid.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Шлюз: принимает все запросы веб-интерфейса и передаёт их нужной службе. */
@SpringBootApplication
@ConfigurationPropertiesScan
// Опрос списка отозванных токенов (RevocationSync)
@EnableScheduling
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
