package io.mediagrid.processing.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.mediagrid.processing.config.ProcessingProperties;
import io.mediagrid.processing.job.JobFailure;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Внутренний интерфейс службы хранения (/internal/files) с токеном службы. Файлы идут потоком
 * между диском и сетью, не накапливаясь в памяти: исходные видео бывают в десятки гигабайт.
 * <p>
 * Экземпляр службы выбирается у балансировщика явно, а запрос идёт клиентом без перехватчиков.
 * Клиент с перехватчиком балансировщика (@LoadBalanced) в Spring собирает тело запроса целиком в памяти
 * (InterceptingClientHttpRequest — буферизующий запрос): перекодированное видео уронило бы службу
 * с OutOfMemoryError.
 */
@Component
public class StorageClient {

    static final String SERVICE = "storage-service";

    private final RestClient rest;
    private final LoadBalancerClient loadBalancer;
    private final ServiceTokens tokens;
    private final CircuitBreaker breaker;

    public StorageClient(LoadBalancerClient loadBalancer, ServiceTokens tokens,
                         @Qualifier("storageBreaker") CircuitBreaker breaker, ProcessingProperties properties) {
        this.rest = RestClient.builder()
                .requestFactory(ClientSetup.requestFactory(Duration.ofSeconds(5), properties.transferTimeout()))
                .build();
        this.loadBalancer = loadBalancer;
        this.tokens = tokens;
        this.breaker = breaker;
    }

    /** Исходный файл — в target. Записи больше нет — {@link JobFailure.Gone}. */
    public void downloadOriginal(UUID mediaId, Path target) {
        call(() -> rest.get().uri(storage("/internal/files/{id}"), mediaId)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer())
                .exchange((request, response) -> {
                    check(response.getStatusCode(), "получение исходного файла");
                    try (InputStream body = response.getBody()) {
                        Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return null;
                }));
    }

    /** Производный файл (thumbnail, preview, playback); прежняя версия того же вида заменяется. */
    public void storeVariant(UUID mediaId, String kind, Path file, String contentType) {
        call(() -> {
            long size;
            try {
                size = Files.size(file);
            } catch (IOException e) {
                throw new JobFailure.Permanent("Нет файла результата " + file.getFileName());
            }
            return rest.put().uri(storage("/internal/files/{id}/variants/{kind}"), mediaId, kind)
                    .header(HttpHeaders.AUTHORIZATION, tokens.bearer())
                    .contentType(MediaType.parseMediaType(contentType))
                    .contentLength(size)
                    .body(new FileSystemResource(file))
                    .exchange((request, response) -> {
                        check(response.getStatusCode(), "сохранение " + kind);
                        return null;
                    });
        });
    }

    /** Шаблон адреса у выбранного балансировщиком экземпляра службы хранения. */
    private String storage(String path) {
        ServiceInstance instance = loadBalancer.choose(SERVICE);
        if (instance == null) {
            throw new JobFailure.Transient("Служба хранения не найдена в регистре служб");
        }
        return instance.getUri() + path;
    }

    /**
     * Через размыкатель цепи. Сбои связи и ответы 5xx — временные; пока цепь разомкнута, обращения
     * сразу завершаются временным сбоем, не дожидаясь тайм-аутов.
     */
    private void call(Supplier<Void> action) {
        try {
            breaker.executeSupplier(() -> {
                try {
                    return action.get();
                } catch (RestClientException e) {
                    throw new JobFailure.Transient("Служба хранения недоступна: " + e.getMessage(), e);
                }
            });
        } catch (CallNotPermittedException e) {
            throw new JobFailure.Transient("Служба хранения недоступна (цепь разомкнута)", e);
        }
    }

    private void check(HttpStatusCode status, String action) {
        if (status.is2xxSuccessful()) {
            return;
        }
        if (status.value() == 404) {
            throw new JobFailure.Gone("Файл удалён из хранилища");
        }
        if (status.value() == 401 || status.value() == 403) {
            tokens.invalidate();
            throw new JobFailure.Transient("Служба хранения отказала в доступе (" + status.value() + "): " + action);
        }
        if (status.is5xxServerError()) {
            throw new JobFailure.Transient("Служба хранения ответила " + status.value() + ": " + action);
        }
        throw new JobFailure.Permanent("Служба хранения отклонила запрос (" + status.value() + "): " + action);
    }
}
