package io.mediagrid.gateway.security;

import java.time.Instant;
import java.util.List;

import io.mediagrid.gateway.security.TokenRevocations.Entry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenRevocationsTest {

    private final TokenRevocations revocations = new TokenRevocations();
    private final Instant later = Instant.now().plusSeconds(900);

    @Test
    void newerGenerationCoversOlderOnes() {
        revocations.replace(List.of(new Entry("u", null, 1, later), new Entry("u", null, 3, later),
                new Entry("u", null, 2, later)));
        assertThat(revocations.isRevoked("u", null, 2)).isTrue();
        assertThat(revocations.isRevoked("u", null, 3)).isFalse();
        assertThat(revocations.isRevoked("other", null, 0)).isFalse();
    }

    @Test
    void expiredRevocationsAreIgnored() {
        Instant past = Instant.now().minusSeconds(1);
        revocations.replace(List.of(new Entry("u", "s", null, past), new Entry("u", null, 5, past)));
        assertThat(revocations.isRevoked("u", "s", 0)).isFalse();
    }

    @Test
    void tokenWithoutSubjectOrSessionIsNotRevoked() {
        revocations.replace(List.of(new Entry("u", "s", null, later)));
        assertThat(revocations.isRevoked(null, null, 0)).isFalse();
        assertThat(revocations.isRevoked("u", null, 0)).isFalse();
        assertThat(revocations.isRevoked("u", "s", 0)).isTrue();
    }
}
