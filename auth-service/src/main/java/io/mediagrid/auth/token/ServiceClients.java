package io.mediagrid.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

import io.mediagrid.auth.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Службы, которые могут получить токен с ролью SERVICE (mediagrid.auth.service-clients). Секреты задаются
 * переменными среды; в памяти хранятся только их отпечатки SHA-256.
 */
@Component
public class ServiceClients {

    /** Короче — слишком легко подобрать; такая служба остаётся без входа. */
    static final int MIN_SECRET_LENGTH = 32;

    private static final Logger log = LoggerFactory.getLogger(ServiceClients.class);

    private final Map<String, byte[]> secretHashes = new HashMap<>();

    public ServiceClients(AuthProperties properties) {
        properties.serviceClients().forEach((name, secret) -> {
            if (secret == null || secret.isBlank()) {
                log.info("Секрет службы {} не задан: токен ей выдаваться не будет", name);
            } else if (secret.length() < MIN_SECRET_LENGTH) {
                log.warn("Секрет службы {} короче {} символов: токен ей выдаваться не будет", name, MIN_SECRET_LENGTH);
            } else {
                secretHashes.put(name, sha256(secret));
            }
        });
    }

    /**
     * Сравнение отпечатков за постоянное время: по времени ответа нельзя подобрать ни секрет, ни его длину.
     * Для неизвестной службы сравнение тоже выполняется, чтобы её нельзя было отличить по времени.
     */
    public boolean isValid(String name, String secret) {
        byte[] expected = secretHashes.getOrDefault(name, new byte[32]);
        boolean matches = MessageDigest.isEqual(expected, sha256(secret));
        return matches && secretHashes.containsKey(name);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
