package io.mediagrid.auth.key;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SigningKeyRepository extends JpaRepository<SigningKey, String> {

    Optional<SigningKey> findFirstByActiveTrueOrderByCreatedAtDesc();
}
