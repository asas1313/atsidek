package eu.atsidek.goals.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import eu.atsidek.TestcontainersConfiguration;
import eu.atsidek.goals.Assumptions;
import eu.atsidek.goals.GoalInput;
import eu.atsidek.goals.GoalMode;
import eu.atsidek.goals.GoalType;
import eu.atsidek.goals.Household;
import eu.atsidek.entitlement.AppUserStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link SavingsGoalStore} - kiekviena užklausa apribota {@code WHERE user_id = ?} (PLAN.md §5/§6
 * task 9), upsert vietoje (tas pats {@code id}), {@code GoalInput} JSON round-trip per
 * {@code assumptions} JSONB stulpelį.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SavingsGoalStoreTest {

    @Autowired SavingsGoalStore store;
    @Autowired AppUserStore appUserStore;
    @Autowired JdbcTemplate jdbc;

    private UUID userA;
    private UUID userB;

    @BeforeEach
    void seedUsers() {
        userA = appUserStore.upsert(UUID.randomUUID(), "google", "subject-a-" + UUID.randomUUID(), "a@example.com").id();
        userB = appUserStore.upsert(UUID.randomUUID(), "google", "subject-b-" + UUID.randomUUID(), "b@example.com").id();
    }

    private static GoalInput sampleInput() {
        return new GoalInput(GoalType.CAR, GoalMode.YEARS, new BigDecimal("5000"), 2, null, null, null, null, null,
                new Household(null, null, null, null, null), new Assumptions(null, null), null);
    }

    @Test
    void insertsNewGoalAndRoundTripsInput() {
        GoalInput input = sampleInput();
        LocalDate targetDate = LocalDate.now().plusYears(2);

        SavingsGoal saved = store.upsert(userA, GoalType.CAR, new BigDecimal("5000"), targetDate, input);

        assertThat(saved.id()).isNotNull();
        assertThat(saved.userId()).isEqualTo(userA);
        assertThat(saved.goalType()).isEqualTo(GoalType.CAR);
        assertThat(saved.amount()).isEqualByComparingTo("5000");
        assertThat(saved.targetDate()).isEqualTo(targetDate);
        assertThat(saved.input()).isEqualTo(input);
        assertThat(saved.createdAt()).isNotNull();

        Optional<SavingsGoal> found = store.findByUserId(userA);
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(saved.id());
        assertThat(found.get().input()).isEqualTo(input);
    }

    @Test
    void upsertReplacesInPlaceKeepingSameId() {
        GoalInput first = sampleInput();
        SavingsGoal firstSaved = store.upsert(userA, GoalType.CAR, new BigDecimal("5000"), LocalDate.now(), first);

        GoalInput second = new GoalInput(GoalType.HOME_DOWN_PAYMENT, GoalMode.YEARS, new BigDecimal("9000"), 3,
                null, null, null, null, null, new Household(null, null, null, null, null),
                new Assumptions(null, null), null);
        SavingsGoal secondSaved = store.upsert(userA, GoalType.HOME_DOWN_PAYMENT, new BigDecimal("9000"),
                LocalDate.now().plusYears(3), second);

        assertThat(secondSaved.id()).isEqualTo(firstSaved.id());
        assertThat(secondSaved.goalType()).isEqualTo(GoalType.HOME_DOWN_PAYMENT);
        assertThat(secondSaved.amount()).isEqualByComparingTo("9000");

        Integer rowCount = jdbc.queryForObject("SELECT count(*) FROM savings_goal WHERE user_id = ?", Integer.class, userA);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void findByIdAndUserIdIsScopedToOwner() {
        SavingsGoal goal = store.upsert(userA, GoalType.CAR, new BigDecimal("5000"), LocalDate.now(), sampleInput());

        assertThat(store.findByIdAndUserId(goal.id(), userA)).isPresent();
        assertThat(store.findByIdAndUserId(goal.id(), userB)).isEmpty();
        assertThat(store.findByIdAndUserId(UUID.randomUUID(), userA)).isEmpty();
    }

    @Test
    void findByUserIdIsEmptyWhenNoGoalSaved() {
        assertThat(store.findByUserId(userA)).isEmpty();
    }

    @Test
    void contributionsAreUpsertedPerMonthAndCanBeDeleted() {
        SavingsGoal goal = store.upsert(userA, GoalType.CAR, new BigDecimal("5000"), LocalDate.now(), sampleInput());
        LocalDate month = LocalDate.of(2026, 1, 1);

        store.upsertContribution(goal.id(), month, new BigDecimal("100"));
        assertThat(store.countContributions(goal.id())).isEqualTo(1);

        store.upsertContribution(goal.id(), month, new BigDecimal("150"));
        List<SavingsContribution> contributions = store.findContributions(goal.id());
        assertThat(contributions).hasSize(1);
        assertThat(contributions.get(0).amount()).isEqualByComparingTo("150");

        store.upsertContribution(goal.id(), LocalDate.of(2026, 2, 1), new BigDecimal("200"));
        assertThat(store.countContributions(goal.id())).isEqualTo(2);

        store.deleteContributions(goal.id());
        assertThat(store.countContributions(goal.id())).isEqualTo(0);
        assertThat(store.findContributions(goal.id())).isEmpty();
    }
}
