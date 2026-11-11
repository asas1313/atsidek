package eu.atsidek.goals.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;

/**
 * Pure (be Spring konteksto, be DB) patikra, kad {@link SavingsPlanRequest} snake_case
 * deserializacija veikia ir kad nežinomi laukai atmetami (PLAN-taupymas-agentas.md A3: "nežinomi
 * laukai → 400 su priežastimi", kaip {@code /api/v1/compare}). SĄMONINGAI naudojamas grynas {@code
 * new ObjectMapper()}, NE Spring Boot automatiškai sukonfigūruotas {@code
 * Jackson2ObjectMapperBuilder} bean'as - šis testas IR BUVO parašytas pirma su Spring'o builder'iu,
 * kuris, patikrinta, IŠJUNGIA {@code FAIL_ON_UNKNOWN_PROPERTIES}; tai privertė
 * {@code SavingsPlanApiController} naudoti savo, atskirą, griežtą {@code ObjectMapper} vietoj
 * automatinio {@code @RequestBody} susiejimo (žr. jo javadoc) - šis testas patikrina TĄ PATĮ
 * mechanizmą, kurį valdiklis realiai naudoja.
 */
class SavingsPlanRequestJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesSnakeCaseFields() throws Exception {
        String json = """
                {"goal":{"type":"HOME_DOWN_PAYMENT","amount":15000,"mode":"YEARS","years":5},
                 "net_monthly_income":3100,"monthly_tax_setaside":900,"monthly_expenses":1350,
                 "household":{"adults":2,"children":2},
                 "assumptions":{"annual_return_pct":0,"annual_inflation_pct":0}}
                """;

        SavingsPlanRequest request = mapper.readValue(json, SavingsPlanRequest.class);

        assertThat(request.goal().type()).isEqualTo(eu.atsidek.goals.GoalType.HOME_DOWN_PAYMENT);
        assertThat(request.goal().mode()).isEqualTo(eu.atsidek.goals.GoalMode.YEARS);
        assertThat(request.goal().years()).isEqualTo(5);
        assertThat(request.netMonthlyIncome()).isEqualByComparingTo("3100");
        assertThat(request.monthlyTaxSetaside()).isEqualByComparingTo("900");
        assertThat(request.monthlyExpenses()).isEqualByComparingTo("1350");
        assertThat(request.household().adults()).isEqualTo(2);
        assertThat(request.household().children()).isEqualTo(2);
        assertThat(request.assumptions().annualReturnPct()).isEqualByComparingTo("0");
    }

    @Test
    void rejectsUnknownTopLevelField() {
        String json = """
                {"goal":{"type":"CAR","amount":1200,"mode":"YEARS","years":5},
                 "net_monthly_income":3100,"unexpected_field":true}
                """;

        assertThatThrownBy(() -> mapper.readValue(json, SavingsPlanRequest.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }

    @Test
    void rejectsUnknownNestedGoalField() {
        String json = """
                {"goal":{"type":"CAR","amount":1200,"mode":"YEARS","years":5,"typo_field":1},
                 "net_monthly_income":3100}
                """;

        assertThatThrownBy(() -> mapper.readValue(json, SavingsPlanRequest.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }
}
