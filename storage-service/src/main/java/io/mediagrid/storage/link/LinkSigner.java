package io.mediagrid.storage.link;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.mediagrid.storage.config.StorageProperties;
import io.mediagrid.storage.file.VariantKind;
import org.springframework.stereotype.Component;

/**
 * Подпись ссылок на файл (HMAC-SHA256). Подписаны номер файла, вид (исходный или производный), срок
 * действия и способ выдачи, поэтому ссылку нельзя продлить или перенести на другой файл. По ссылке файл отдаётся без токена:
 * так работает воспроизведение в теге video, который не умеет передавать заголовок Authorization.
 */
@Component
public class LinkSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public LinkSigner(StorageProperties properties) {
        this.key = new SecretKeySpec(properties.linkSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String sign(UUID mediaId, VariantKind kind, Instant expires, boolean download) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac(payload(mediaId, kind, expires, download)));
    }

    /** Сравнение за постоянное время: по времени ответа нельзя подобрать подпись. */
    public boolean isValid(UUID mediaId, VariantKind kind, Instant expires, boolean download, String signature) {
        byte[] expected = mac(payload(mediaId, kind, expires, download));
        byte[] given;
        try {
            given = Base64.getUrlDecoder().decode(signature);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, given);
    }

    private static String payload(UUID mediaId, VariantKind kind, Instant expires, boolean download) {
        return mediaId + ":" + kind.pathName() + ":" + expires.getEpochSecond() + ":" + (download ? "download" : "inline");
    }

    private byte[] mac(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
