package eu.atsidek.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import eu.atsidek.TestcontainersConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@link EntitlementStore#grant} - naujas rašymo metodas (2026-11-13, B6 gyvo eval'o CI, žr.
 * {@code eu.atsidek.evalci.EvalTokenService}). Iki šiol {@code EntitlementStore} buvo tik
 * skaitymo repository (žr. klasės javadoc) - šis testas tikrina, kad {@code grant} parašytą
 * eilutę realiai randa {@link #findByUserAndFeature}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EntitlementStoreTest {

    @Autowired
    EntitlementStore store;

    @Autowired
    AppUserStore appUserStore;

    private UUID newUser() {
        return appUserStore.upsert(UUID.randomUUID(), "google", "subject-" + UUID.randomUUID(),
                "entitlement-store-test@example.com").id();
    }

    @Test
    void grantedRowIsFoundByUserAndFeature() {
        UUID userId = newUser();

        assertThat(store.findByUserAndFeature(userId, Feature.SAVINGS_MULTI_GOAL)).isEmpty();

        store.grant(userId, Feature.SAVINGS_MULTI_GOAL, "BETA", Instant.parse("2026-01-01T00:00:00Z"));

        var found = store.findByUserAndFeature(userId, Feature.SAVINGS_MULTI_GOAL);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).userId()).isEqualTo(userId);
        assertThat(found.get(0).feature()).isEqualTo(Feature.SAVINGS_MULTI_GOAL);
        assertThat(found.get(0).source()).isEqualTo("BETA");
        assertThat(found.get(0).validFrom()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(found.get(0).validUntil()).isNull();
    }

    @Test
    void grantDoesNotAffectAnotherFeatureForTheSameUser() {
        UUID userId = newUser();

        store.grant(userId, Feature.SAVINGS_MULTI_GOAL, "BETA", Instant.now());

        assertThat(store.findByUserAndFeature(userId, Feature.SAVINGS_IV_LINK)).isEmpty();
    }

    @Test
    void grantDoesNotAffectAnotherUserWithTheSameFeature() {
        UUID userA = newUser();
        UUID userB = newUser();

        store.grant(userA, Feature.SAVINGS_SCENARIOS, "BETA", Instant.now());

        assertThat(store.findByUserAndFeature(userB, Feature.SAVINGS_SCENARIOS)).isEmpty();
    }

    @Test
    void callingGrantTwiceCreatesTwoRowsNoUniqueConstraint() {
        // Documents the deliberate design (see EntitlementStore.grant's javadoc): no unique
        // constraint on (user_id, feature) - callers must check findByUserAndFeature themselves
        // first if they want idempotency (EvalTokenService does).
        UUID userId = newUser();

        store.grant(userId, Feature.SAVINGS_MULTI_GOAL, "BETA", Instant.now());
        store.grant(userId, Feature.SAVINGS_MULTI_GOAL, "BETA", Instant.now());

        assertThat(store.findByUserAndFeature(userId, Feature.SAVINGS_MULTI_GOAL)).hasSize(2);
    }
}
