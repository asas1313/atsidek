package eu.atsidek.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import eu.atsidek.TestcontainersConfiguration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link AppUserStore} - idempotentis upsert, raktas (auth_provider, provider_subject), ne email
 * (žr. ADR-0001).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AppUserStoreTest {

    @Autowired
    AppUserStore store;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void firstLoginInsertsNewRow() {
        String subject = "subject-" + UUID.randomUUID();

        assertThat(store.findByProviderSubject("google", subject)).isEmpty();

        AppUser created = store.upsert(UUID.randomUUID(), "google", subject, "first@example.com");

        assertThat(created.authProvider()).isEqualTo("google");
        assertThat(created.providerSubject()).isEqualTo(subject);
        assertThat(created.email()).isEqualTo("first@example.com");
        assertThat(created.createdAt()).isNotNull();

        Optional<AppUser> found = store.findByProviderSubject("google", subject);
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(created.id());

        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE auth_provider = ? AND provider_subject = ?",
                Integer.class, "google", subject);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void repeatLoginDoesNotDuplicateOrChangeId() {
        String subject = "subject-" + UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        AppUser first = store.upsert(firstId, "google", subject, "repeat@example.com");

        UUID secondAttemptId = UUID.randomUUID();
        AppUser second = store.upsert(secondAttemptId, "google", subject, "repeat@example.com");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.id()).isNotEqualTo(secondAttemptId);

        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE auth_provider = ? AND provider_subject = ?",
                Integer.class, "google", subject);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void repeatLoginWithChangedEmailUpdatesOnlyEmail() {
        String subject = "subject-" + UUID.randomUUID();
        AppUser first = store.upsert(UUID.randomUUID(), "google", subject, "old@example.com");

        AppUser second = store.upsert(UUID.randomUUID(), "google", subject, "new@example.com");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.authProvider()).isEqualTo("google");
        assertThat(second.providerSubject()).isEqualTo(subject);
        assertThat(second.email()).isEqualTo("new@example.com");

        Optional<AppUser> found = store.findByProviderSubject("google", subject);
        assertThat(found).isPresent();
        assertThat(found.get().email()).isEqualTo("new@example.com");
        assertThat(found.get().id()).isEqualTo(first.id());
    }

    @Test
    void findByProviderSubjectReturnsEmptyForUnknownSubject() {
        assertThat(store.findByProviderSubject("google", "unknown-" + UUID.randomUUID())).isEmpty();
    }
}
