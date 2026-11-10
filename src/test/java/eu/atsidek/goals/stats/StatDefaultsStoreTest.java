package eu.atsidek.goals.stats;

import static org.assertj.core.api.Assertions.assertThat;

import eu.atsidek.TestcontainersConfiguration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Patikrina, kad V3/V4 Flyway migracijos sukuria lentelę ir seed duomenis realiame PostgreSQL (Testcontainers). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StatDefaultsStoreTest {

    @Autowired
    StatDefaultsStore store;

    @Test
    void findsExactMockupSegment() {
        Optional<StatDefaultsStore.Row> income = store.find("income", "members=4;workers=2");
        assertThat(income).isPresent();
        assertThat(income.get().value()).isEqualByComparingTo("3200.00");
        assertThat(income.get().illustrative()).isTrue();

        Optional<StatDefaultsStore.Row> expenses = store.find("expenses", "members=4;workers=2");
        assertThat(expenses).isPresent();
        assertThat(expenses.get().value()).isEqualByComparingTo("1900.00");
    }

    @Test
    void findsGeneralFallbackSegment() {
        assertThat(store.find("income", "general")).isPresent();
        assertThat(store.find("expenses", "general")).isPresent();
    }

    @Test
    void findsTypicalAmountForAllGoalTypes() {
        for (String goalType : List.of("CHILD", "CAR", "HOME_DOWN_PAYMENT", "OTHER")) {
            Optional<StatDefaultsStore.Row> row = store.find("typical_amount", "goalType=" + goalType);
            assertThat(row).as("typical_amount for " + goalType).isPresent();
            assertThat(row.get().value().signum()).isPositive();
        }
    }

    @Test
    void doesNotSeedAssumptionKeys() {
        // K5/F1 PR #22: annualInflation/annualReturn NĖRA stat_defaults įrašai (kodo konstantos vietoj to).
        assertThat(store.findAllByKey("annualInflation")).isEmpty();
        assertThat(store.findAllByKey("annualReturn")).isEmpty();
    }

    @Test
    void findAllByKeyReturnsAllHouseholdSegments() {
        List<StatDefaultsStore.Row> income = store.findAllByKey("income");
        assertThat(income).extracting(StatDefaultsStore.Row::segment)
                .contains("members=1;workers=1", "members=2;workers=1", "members=2;workers=2",
                        "members=3;workers=2", "members=4;workers=2", "general");
    }

    @Test
    void returnsEmptyForUnknownSegment() {
        assertThat(store.find("income", "members=999;workers=999")).isEmpty();
    }
}
