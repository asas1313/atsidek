package eu.atsidek.goals.plan;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import eu.atsidek.goals.GoalMode;
import eu.atsidek.goals.GoalType;
import java.math.BigDecimal;

/**
 * {@code POST /api/v1/savings/plan} užklausa (PLAN-taupymas-agentas.md A3) - snake_case riba virš
 * esamo {@code eu.atsidek.goals.GoalInput}/{@code Household}/{@code Assumptions} (sprendimas #1:
 * naujo variklio nekurti, tik plonas API sluoksnis).
 * <p>
 * SVARBU dėl nežinomų laukų ("→ 400", kaip {@code /api/v1/compare}): patikrinta ({@code
 * SavingsPlanRequestJsonTest}), kad Spring Boot numatytasis {@code Jackson2ObjectMapperBuilder}
 * IŠJUNGIA {@code FAIL_ON_UNKNOWN_PROPERTIES} - vadinasi, automatinis {@code @RequestBody} susiejimas
 * su Spring'o bean'u NEATMESTŲ nežinomų laukų, ir {@code @JsonIgnoreProperties(ignoreUnknown = false)}
 * ČIA TOS problemos NEIŠSPRENDŽIA (ta anotacija gali tik ATPALAIDUOTI, ne SUGRIEŽTINTI globalų
 * nustatymą). Todėl griežtumas užtikrinamas {@code SavingsPlanApiController}'yje - atskiru, NE
 * Spring'o konfigūruotu {@code ObjectMapper} (jo numatytasis Jackson elgesys jau griežtas, be
 * papildomo konfigūravimo). Ši klasė tą griežtą mapper'į tiesiog naudoja tikslo tipu - šičia
 * papildomos anotacijos nereikia ir ji sąmoningai NEDEDAMA, kad nesukurtų klaidingo įspūdžio, jog
 * klasė pati savaime apsisaugo nuo nežinomų laukų nepriklausomai nuo to, koks {@code ObjectMapper}
 * ją naudoja ({@code eu.atsidek.goals.web.GoalsApiController.CompareRequest}-stiliaus klaida, kurios
 * šis PR sąmoningai nekartoja).
 *
 * @param goal                 taupymo tikslas; privalomas
 * @param netMonthlyIncome     grynosios mėnesio pajamos; privalomas (PLAN.md principas: pajamos
 *                             niekada neimamos iš statistikos - šioje sinchroninėje API nėra
 *                             patikslinančio klausimo mechanizmo, todėl trūkstamos pajamos → 400)
 * @param monthlyTaxSetaside   mėnesio mokesčių atidėjimas; nebūtinas, numatytasis 0 - NE statistika
 * @param monthlyExpenses      mėnesio išlaidos; {@code null} - sprendžiama iš {@code stat_defaults}
 * @param household            namų ūkio sudėtis (statistikos segmentui); nebūtinas
 * @param assumptions          grąžos/infliacijos prielaidos; nebūtinas
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SavingsPlanRequest(
        GoalRequest goal,
        BigDecimal netMonthlyIncome,
        BigDecimal monthlyTaxSetaside,
        BigDecimal monthlyExpenses,
        HouseholdRequest household,
        AssumptionsRequest assumptions) {

    public SavingsPlanRequest {
        if (household == null) {
            household = new HouseholdRequest(null, null);
        }
        if (assumptions == null) {
            assumptions = new AssumptionsRequest(null, null);
        }
    }

    /**
     * @param type                 tikslo tipas ({@code GoalType})
     * @param mode                 termino apibrėžimo būdas ({@code GoalMode})
     * @param amount               tikslo suma šiandienos kainomis
     * @param years                metų skaičius (mode = YEARS)
     * @param targetYear           tikslo metai (mode = DATE)
     * @param targetMonth          tikslo mėnuo 1-12 (mode = DATE, nebūtinas)
     * @param childCurrentAge      vaiko dabartinis amžius (mode = CHILD_AGE)
     * @param childTargetAge       vaiko tikslinis amžius (mode = CHILD_AGE)
     * @param monthlyContribution  mėnesio įmoka (mode = WHEN)
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GoalRequest(
            GoalType type,
            GoalMode mode,
            BigDecimal amount,
            Integer years,
            Integer targetYear,
            Integer targetMonth,
            Integer childCurrentAge,
            Integer childTargetAge,
            BigDecimal monthlyContribution) {
    }

    /**
     * @param adults   suaugusių namų ūkio narių skaičius
     * @param children vaikų skaičius
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record HouseholdRequest(Integer adults, Integer children) {
    }

    /**
     * @param annualReturnPct    metinė investicijų grąža procentais; {@code null} - numatytoji (0 %)
     * @param annualInflationPct metinė infliacija procentais; {@code null} - numatytoji (2,5 %)
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AssumptionsRequest(BigDecimal annualReturnPct, BigDecimal annualInflationPct) {
    }
}
