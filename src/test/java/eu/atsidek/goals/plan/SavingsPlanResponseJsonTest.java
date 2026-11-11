package eu.atsidek.goals.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.atsidek.entitlement.Feature;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure (be Spring konteksto) JSON formos patikra {@link SavingsPlanResponse} - {@code premium}
 * raktas turi visiškai IŠNYKTI JSON'e, kai reikšmė {@code null} (anonimui), ir likti kaip {@code {}},
 * kai reikšmė yra (prisijungusiam), žr. PLAN-taupymas-agentas.md A3/A4 "anonimui jos nėra".
 */
class SavingsPlanResponseJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static Core sampleCore() {
        return new Core(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true, "tip", List.of(), "disclaimer");
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
                eu.atsidek.goals.Origin.STATISTICS, "VDA", "2026-01-01", true);
        Core core = new Core(new BigDecimal("200.00"), new BigDecimal("500.00"), new BigDecimal("40.00"), true,
                "tip", List.of(entry), "disclaimer");
        SavingsPlanResponse response = new SavingsPlanResponse(Tier.FREE, core, List.of(), null);

        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"monthly_contribution\":200.00");
        assertThat(json).contains("\"free_cash_monthly\":500.00");
        assertThat(json).contains("\"share_of_free_cash_pct\":40.00");
        assertThat(json).contains("\"general_tip\":\"tip\"");
        assertThat(json).contains("\"source_date\":\"2026-01-01\"");
        assertThat(json).contains("\"is_illustrative\":true");
    }
}
