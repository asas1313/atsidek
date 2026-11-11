package eu.atsidek.goals.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit testai be DB: {@link StatDefaultsStore} pakeičiamas fake'u su fiksuotais duomenimis, atitinkančiais
 * V4__seed_stat_defaults.sql turinį (žr. ten pačias reikšmes/segmentus).
 */
class StatDefaultsServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 1, 1);

    private StatDefaultsService serviceWithSeedLikeData() {
        Map<String, List<StatDefaultsStore.Row>> byKey = Map.of(
                "income", List.of(
                        row("income", "members=1;workers=1", "1600"),
                        row("income", "members=2;workers=1", "1600"),
                        row("income", "members=2;workers=2", "3200"),
                        row("income", "members=3;workers=2", "3200"),
                        row("income", "members=4;workers=2", "3200"),
                        row("income", "general", "2500")),
                "expenses", List.of(
                        row("expenses", "members=1;workers=1", "900"),
                        row("expenses", "members=2;workers=1", "1350"),
                        row("expenses", "members=2;workers=2", "1350"),
                        row("expenses", "members=3;workers=2", "1600"),
                        row("expenses", "members=4;workers=2", "1900"),
                        row("expenses", "general", "1400")),
                "typical_amount", List.of(
                        row("typical_amount", "goalType=CHILD", "20000"),
                        row("typical_amount", "goalType=CAR", "25000"),
                        row("typical_amount", "goalType=HOME_DOWN_PAYMENT", "15000"),
                        row("typical_amount", "goalType=OTHER", "5000")));
        return new StatDefaultsService(new FakeStore(byKey));
    }

    private static StatDefaultsStore.Row row(String key, String segment, String value) {
        return new StatDefaultsStore.Row(key, segment, new BigDecimal(value), "EUR_PER_MONTH",
                "test-source", "https://example.org/test", AS_OF, true);
    }

    @Test
    void exactSegmentMatch() {
        StatDefaultsResponse r = serviceWithSeedLikeData().resolve(2, 2, null, null);
        assertThat(r.income().value()).isEqualByComparingTo("3200");
        assertThat(r.expenses().value()).isEqualByComparingTo("1350");
        assertThat(r.income().origin()).isEqualTo("STATISTICS");
    }

    @Test
    void mockupSegmentMatchesExactly() {
        StatDefaultsResponse r = serviceWithSeedLikeData().resolve(4, 2, null, null);
        assertThat(r.income().value()).isEqualByComparingTo("3200");
        assertThat(r.expenses().value()).isEqualByComparingTo("1900");
    }

    @Test
    void fallsBackToNearestSegmentWhenExactMissing() {
        // (5,3) nėra seed'e; artimiausias pagal Manheteno atstumą - (4,2): |4-5|+|2-3| = 2.
        StatDefaultsResponse r = serviceWithSeedLikeData().resolve(5, 3, null, null);
        assertThat(r.income().value()).isEqualByComparingTo("3200");
        assertThat(r.expenses().value()).isEqualByComparingTo("1900");
    }

    @Test
    void fallsBackUsingOnlyProvidedDimensionWhenOtherIsMissing() {
        // workers nenurodytas - artimiausias pagal members=3 tiksliai sutampa su members=3;workers=2.
        StatDefaultsResponse r = serviceWithSeedLikeData().resolve(3, null, null, null);
        assertThat(r.expenses().value()).isEqualByComparingTo("1600");
    }

    @Test
    void usesGeneralSegmentWhenNoHouseholdInfoGiven() {
        StatDefaultsResponse r = serviceWithSeedLikeData().resolve(null, null, null, null);
        assertThat(r.income().value()).isEqualByComparingTo("2500");
        assertThat(r.expenses().value()).isEqualByComparingTo("1400");
    }

    @Test
    void returnsTypicalAmountOnlyWhenGoalTypeGiven() {
        StatDefaultsResponse withGoal = serviceWithSeedLikeData().resolve(4, 2, null, "CHILD");
        assertThat(withGoal.typicalAmount()).isNotNull();
        assertThat(withGoal.typicalAmount().value()).isEqualByComparingTo("20000");

        StatDefaultsResponse withoutGoal = serviceWithSeedLikeData().resolve(4, 2, null, null);
        assertThat(withoutGoal.typicalAmount()).isNull();
    }

    @Test
    void returnsAssumptionConstantsForInflationAndReturn() {
        StatDefaultsResponse r = serviceWithSeedLikeData().resolve(null, null, null, null);
        assertThat(r.annualInflation().value()).isEqualByComparingTo(StatDefaultsService.DEFAULT_ANNUAL_INFLATION);
        assertThat(r.annualInflation().origin()).isEqualTo("ASSUMPTION");
        assertThat(r.annualInflation().sourceUrl()).isNull();
        assertThat(r.annualInflation().asOf()).isNull();
        assertThat(r.annualInflation().illustrative()).isFalse();

        assertThat(r.annualReturn().value()).isEqualByComparingTo(StatDefaultsService.DEFAULT_ANNUAL_RETURN);
        // A0 sprendimas #2: numatytoji grąža konkrečiai 0 %, ne tik "kas tuo metu yra konstantoje" –
        // literalus assertas, kad grąžinimas prie 3 % (konstantos pakeitimas) šį testą sulaužytų.
        assertThat(r.annualReturn().value()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(r.annualReturn().origin()).isEqualTo("ASSUMPTION");
    }

    @Test
    void rejectsNegativeMembers() {
        assertThatThrownBy(() -> serviceWithSeedLikeData().resolve(-1, 1, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsNegativeWorkers() {
        assertThatThrownBy(() -> serviceWithSeedLikeData().resolve(2, -1, null, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsMoreWorkersThanMembers() {
        assertThatThrownBy(() -> serviceWithSeedLikeData().resolve(2, 5, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("dirbančiųjų");
    }

    @Test
    void rejectsUnknownGoalType() {
        assertThatThrownBy(() -> serviceWithSeedLikeData().resolve(2, 2, null, "BOAT"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("nežinomas tikslo tipas");
    }

    /** Fake/stub store (be DB) – testo duomenys atitinka seed migracijos segmentus. */
    private static final class FakeStore extends StatDefaultsStore {
        private final Map<String, List<Row>> byKey;

        FakeStore(Map<String, List<Row>> byKey) {
            super(null);
            this.byKey = byKey;
        }

        @Override
        public Optional<Row> find(String key, String segment) {
            return byKey.getOrDefault(key, List.of()).stream().filter(r -> r.segment().equals(segment)).findFirst();
        }

        @Override
        public List<Row> findAllByKey(String key) {
            return byKey.getOrDefault(key, List.of());
        }
    }
}
