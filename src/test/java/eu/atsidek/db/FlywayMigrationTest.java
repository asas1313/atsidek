package eu.atsidek.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.atsidek.TestcontainersConfiguration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Patikrina, kad V5-V9 Flyway migracijos pritaikomos švariai realiame PostgreSQL
 * (Testcontainers) ir kad lentelių stulpeliai/apribojimai (CHECK/UNIQUE/FK ON DELETE CASCADE)
 * atitinka docs/adr/0002-entitlements-data-model-and-beta-flag.md schemą.
 *
 * <p>Čia nėra entity/repository klasių (tos priklauso vėlesnėms užduotims) - testas kalba
 * tiesiai su DB per JdbcTemplate, tik tam, kad patvirtintų DDL.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayMigrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void contextLoadsAndMigrationsApplyCleanly() {
        // Jei Spring kontekstas (ir Flyway) pakyla be klaidų, šis testas tiesiog praeina.
        assertThat(jdbc).isNotNull();
    }

    @Test
    void appUserHasExpectedShapeAndUniqueIdentity() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app_user (id, auth_provider, provider_subject, email) VALUES (?, ?, ?, ?)",
                id, "google", "subject-1", "user@example.com");

        OffsetDateTime createdAt = jdbc.queryForObject(
                "SELECT created_at FROM app_user WHERE id = ?", OffsetDateTime.class, id);
        assertThat(createdAt).isNotNull();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO app_user (id, auth_provider, provider_subject, email) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), "google", "subject-1", "other@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void appSettingHasExpectedShapeAndNoSeedRow() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM app_setting WHERE setting_key = 'goals_beta_mode_enabled'",
                Integer.class);
        assertThat(count).isZero();

        jdbc.update(
                "INSERT INTO app_setting (setting_key, setting_value) VALUES (?, ?)",
                "goals_beta_mode_enabled", "true");
        String value = jdbc.queryForObject(
                "SELECT setting_value FROM app_setting WHERE setting_key = 'goals_beta_mode_enabled'",
                String.class);
        assertThat(value).isEqualTo("true");
    }

    @Test
    void entitlementCascadesOnAppUserDeleteAndHasUserFeatureIndex() {
        UUID userId = insertAppUser("subject-entitlement");
        jdbc.update(
                "INSERT INTO entitlement (user_id, feature, source, valid_from) VALUES (?, ?, ?, now())",
                userId, "SAVINGS_TRACKING", "BETA");

        Integer indexCount = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'entitlement' "
                        + "AND indexdef LIKE '%(user_id, feature)%'",
                Integer.class);
        assertThat(indexCount).isEqualTo(1);

        jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM entitlement WHERE user_id = ?", Integer.class, userId);
        assertThat(remaining).isZero();
    }

    @Test
    void savingsGoalEnforcesAmountCheckAndOneGoalPerUser() {
        UUID userId = insertAppUser("subject-goal-1");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO savings_goal (id, user_id, goal_type, amount, assumptions) "
                        + "VALUES (?, ?, ?, ?, '{}'::jsonb)",
                UUID.randomUUID(), userId, "HOME_DOWN_PAYMENT", new java.math.BigDecimal("0.00")))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID goalId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO savings_goal (id, user_id, goal_type, amount, target_date, assumptions) "
                        + "VALUES (?, ?, ?, ?, ?, '{}'::jsonb)",
                goalId, userId, "HOME_DOWN_PAYMENT", new java.math.BigDecimal("1000.00"),
                java.sql.Date.valueOf("2030-01-01"));

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO savings_goal (id, user_id, goal_type, amount, assumptions) "
                        + "VALUES (?, ?, ?, ?, '{}'::jsonb)",
                UUID.randomUUID(), userId, "CAR", new java.math.BigDecimal("500.00")))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM savings_goal WHERE id = ?", Integer.class, goalId);
        assertThat(remaining).isZero();
    }

    @Test
    void savingsContributionEnforcesAmountMonthAlignmentAndUniqueness() {
        UUID userId = insertAppUser("subject-goal-2");
        UUID goalId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO savings_goal (id, user_id, goal_type, amount, assumptions) "
                        + "VALUES (?, ?, ?, ?, '{}'::jsonb)",
                goalId, userId, "CAR", new java.math.BigDecimal("2000.00"));

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO savings_contribution (goal_id, month, amount) VALUES (?, ?, ?)",
                goalId, java.sql.Date.valueOf("2025-01-15"), new java.math.BigDecimal("100.00")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO savings_contribution (goal_id, month, amount) VALUES (?, ?, ?)",
                goalId, java.sql.Date.valueOf("2025-01-01"), new java.math.BigDecimal("-1.00")))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update(
                "INSERT INTO savings_contribution (goal_id, month, amount) VALUES (?, ?, ?)",
                goalId, java.sql.Date.valueOf("2025-01-01"), new java.math.BigDecimal("100.00"));

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO savings_contribution (goal_id, month, amount) VALUES (?, ?, ?)",
                goalId, java.sql.Date.valueOf("2025-01-01"), new java.math.BigDecimal("50.00")))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("DELETE FROM savings_goal WHERE id = ?", goalId);
        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM savings_contribution WHERE goal_id = ?", Integer.class, goalId);
        assertThat(remaining).isZero();
    }

    private UUID insertAppUser(String subject) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO app_user (id, auth_provider, provider_subject, email) VALUES (?, ?, ?, ?)",
                id, "google", subject, subject + "@example.com");
        return id;
    }
}
