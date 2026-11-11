package eu.atsidek.goals.plan;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.math.BigDecimal;
import java.util.List;

/**
 * Nemokama {@code /api/v1/savings/plan} atsakymo dalis - matoma visiems, anonimui ir prisijungusiam
 * (PLAN-taupymas-agentas.md A3). Skaičiai čia visada iš {@code eu.atsidek.goals.GoalsEngine} per
 * {@link SavingsPlanService} - niekada iš naujo neskaičiuojami kitaip.
 *
 * @param monthlyContribution  mėnesio įmoka
 * @param freeCashMonthly      laisvi pinigai/mėn. ({@code net_monthly_income - monthly_tax_setaside - monthly_expenses})
 * @param shareOfFreeCashPct   įmokos dalis nuo laisvų pinigų, procentais
 * @param fits                 ar tikslas telpa į laisvus pinigus (ne {@code UNREALISTIC}/{@code NO_FREE_CASH})
 * @param generalTip           deterministinis patarimo šablonas, priklausantis nuo verdikto
 * @param assumptions          reikšmės, kurių vartotojas nenurodė (žr. {@link AssumptionEntry})
 * @param disclaimer           fiksuotas teisinis atsiribojimas
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Core(
        BigDecimal monthlyContribution,
        BigDecimal freeCashMonthly,
        BigDecimal shareOfFreeCashPct,
        boolean fits,
        String generalTip,
        List<AssumptionEntry> assumptions,
        String disclaimer) {
}
