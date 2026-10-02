package io.mediagrid.auth.key;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "signing_keys")
public class SigningKey {

    @Id
    private String kid;

    @Column(nullable = false)
    private String privateKey;

    @Column(nullable = false)
    private String publicKey;

    private boolean active;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected SigningKey() {
    }

    public SigningKey(String kid, String privateKey, String publicKey) {
        this.kid = kid;
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.active = true;
        this.createdAt = Instant.now();
    }

    public String getKid() {
        return kid;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public boolean isActive() {
        return active;
    }
}
