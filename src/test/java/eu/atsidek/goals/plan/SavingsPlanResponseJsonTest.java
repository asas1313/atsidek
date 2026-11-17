package eu.atsidek.goals.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import eu.atsidek.entitlement.Feature;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure (be Spring konteksto) JSON formos patikra {@link SavingsPlanResponse} - {@code premium}
 * raktas turi visiškai IŠNYKTI JSON'e, kai reikšmė {@code null} (anonimui), ir likti kaip {@code {}},
 * kai reikšmė yra (prisijungusiam), žr. PLAN-taupymas-agentas.md A3/A4 "anonimui jos nėra".
 */
class SavingsPlanResponseJsonTest {

    // reach_date (java.time.LocalDate) needs JSR310 registered explicitly here - production uses
    // Spring Boot's auto-configured ObjectMapper, which already registers it; this test's own
    // plain `new ObjectMapper()` does not.
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static Core sampleCore() {
        return new Core(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true, "tip", List.of(), "disclaimer",
                true, null);
    }

    @Test
    void premiumKeyIsAbsentWhenNull() throws Exception {
        SavingsPlanResponse response = new SavingsPlanResponse(Tier.FREE, sampleCore(),
                List.of(Feature.SAVINGS_MULTI_GOAL, Feature.SAVINGS_IV_LINK, Feature.SAVINGS_SCENARIOS), null);

        String json = mapper.writeValueAsString(response);

        assertThat(json).doesNotContain("\"premium\"");
        assertThat(json).contains("\"tier\":\"FREE\"");
        assertThat(json).contains("\"locked_features\"");
    }

    @Test
    void premiumKeyIsPresentAsEmptyObjectWhenAuthenticated() throws Exception {
        SavingsPlanResponse response = new SavingsPlanResponse(Tier.PREMIUM, sampleCore(), List.of(), new Premium());

        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"premium\":{}");
    }

    @Test
    void coreAndAssumptionsUseSnakeCase() throws Exception {
        AssumptionEntry entry = new AssumptionEntry("monthly_expenses", new BigDecimal("1350.00"),
                eu.atsidek.goals.Origin.STATISTICS, "VDA", "2026-01-01", true, true);
        Core core = new Core(new BigDecimal("200.00"), new BigDecimal("500.00"), new BigDecimal("40.00"), true,
                "tip", List.of(entry), "disclaimer", true, LocalDate.of(2027, 3, 1));
        SavingsPlanResponse response = new SavingsPlanResponse(Tier.FREE, core, List.of(), null);

        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"monthly_contribution\":200.00");
        assertThat(json).contains("\"free_cash_monthly\":500.00");
        assertThat(json).contains("\"share_of_free_cash_pct\":40.00");
        assertThat(json).contains("\"general_tip\":\"tip\"");
        assertThat(json).contains("\"source_date\":\"2026-01-01\"");
        assertThat(json).contains("\"is_illustrative\":true");
        assertThat(json).contains("\"needs_review\":true");
    }

    @Test
    void reachableAndReachDateAreSnakeCaseAndNullReachDateSerializesExplicitly() throws Exception {
        // Owner instruction (2026-11-16, after B6 eval #11): WHEN mode's goal-reach date is a
        // core (free) field - must survive JSON serialization under its snake_case name, and a
        // null reach_date (non-WHEN modes, where the term is an input, not an output) must stay
        // an explicit JSON null, not silently vanish, so the Python tool wrapper can tell "not
        // applicable" apart from "field missing".
        Core core = new Core(new BigDecimal("200.00"), new BigDecimal("500.00"), new BigDecimal("40.00"), true,
                "tip", List.of(), "disclaimer", false, null);
        SavingsPlanResponse response = new SavingsPlanResponse(Tier.FREE, core, List.of(), null);

        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"reachable\":false");
        assertThat(json).contains("\"reach_date\":null");
    }
}
