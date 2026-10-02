package io.mediagrid.auth.key;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ключи RSA для подписи токенов доступа. При первом запуске ключ создаётся и сохраняется в базе,
 * поэтому выданные токены переживают перезапуск и одинаково проверяются на всех экземплярах службы.
 */
@Service
public class SigningKeyService {

    private static final Logger log = LoggerFactory.getLogger(SigningKeyService.class);

    private final SigningKeyRepository repository;
    private final JdbcTemplate jdbc;

    private final JWKSet jwkSet;
    private final String activeKid;

    public SigningKeyService(SigningKeyRepository repository, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.repository = repository;
        this.jdbc = jdbc;
        SigningKey active = tx.execute(status -> ensureActiveKey());
        this.activeKid = active.getKid();
        this.jwkSet = new JWKSet(repository.findAll().stream().map(SigningKeyService::toJwk).toList());
    }

    /** Ключ, которым подписываются новые токены. */
    public String activeKid() {
        return activeKid;
    }

    /** Все ключи с закрытыми частями — для выпуска и проверки токенов внутри службы. */
    public JWKSource<SecurityContext> jwkSource() {
        return (selector, context) -> selector.select(jwkSet);
    }

    /** Только открытые части ключей — то, что публикуется для других служб. */
    public JWKSet publicJwkSet() {
        return jwkSet.toPublicJWKSet();
    }

    private SigningKey ensureActiveKey() {
        // Блокировка на время транзакции: два экземпляра, запущенные одновременно, не создадут по ключу каждый
        jdbc.execute("SELECT pg_advisory_xact_lock(hashtext('auth.signing_keys'))");
        return repository.findFirstByActiveTrueOrderByCreatedAtDesc().orElseGet(() -> {
            KeyPair pair = generateRsaKeyPair();
            Base64.Encoder base64 = Base64.getEncoder();
            SigningKey key = new SigningKey(UUID.randomUUID().toString(),
                    base64.encodeToString(pair.getPrivate().getEncoded()),
                    base64.encodeToString(pair.getPublic().getEncoded()));
            log.info("Создан новый ключ подписи токенов {}", key.getKid());
            return repository.save(key);
        });
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWK toJwk(SigningKey key) {
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            Base64.Decoder base64 = Base64.getDecoder();
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                    new X509EncodedKeySpec(base64.decode(key.getPublicKey())));
            RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(base64.decode(key.getPrivateKey())));
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(key.getKid())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Не удалось прочитать ключ подписи " + key.getKid(), e);
        }
    }
}
