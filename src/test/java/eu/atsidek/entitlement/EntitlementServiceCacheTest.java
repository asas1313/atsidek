package eu.atsidek.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import eu.atsidek.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link EntitlementService}'s beta-flag skaitymas turi ~30s TTL talpyklą virš {@code app_setting}
 * skaitymo realiame Postgres. Testas paslenka laikrodį, ne miega (žr. plano §6 task 5).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EntitlementServiceCacheTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntitlementStore store;

    @Test
    void picksUpBetaFlagChangeOnlyAfterCacheTtlExpires() {
        jdbc.update("DELETE FROM app_setting WHERE setting_key = 'goals_beta_mode_enabled'");
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        EntitlementService service = new EntitlementService(store, clock);
        UUID userId = UUID.randomUUID();

        assertThat(service.hasFeature(userId, Feature.SAVINGS_TRACKING)).isFalse();

        jdbc.update(
                "INSERT INTO app_setting (setting_key, setting_value) VALUES ('goals_beta_mode_enabled', 'true')");

        // Talpykla dar nepasenusi (< 30s) - sena reikšmė.
        clock.advance(Duration.ofSeconds(10));
        assertThat(service.hasFeature(userId, Feature.SAVINGS_TRACKING)).isFalse();

        // Talpykla pasenusi (> 30s nuo pirmo skaitymo) - naują DB reikšmę turi pamatyti.
        clock.advance(Duration.ofSeconds(25));
        assertThat(service.hasFeature(userId, Feature.SAVINGS_TRACKING)).isTrue();

        jdbc.update("DELETE FROM app_setting WHERE setting_key = 'goals_beta_mode_enabled'");
    }
}
