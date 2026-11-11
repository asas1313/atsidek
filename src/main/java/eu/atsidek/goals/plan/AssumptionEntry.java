package eu.atsidek.goals.plan;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import eu.atsidek.goals.Origin;
import java.math.BigDecimal;

/**
 * Viena {@code core.assumptions} atsakymo eilutė (PLAN-taupymas-agentas.md A2/A3): reikšmė, kurios
 * vartotojas NEnurodė tiesiogiai, todėl jai reikia kilmės ženkliuko. {@code USER} kilmės reikšmių
 * (vartotojo tiesiogiai įvestų) čia sąmoningai NĖRA - žr. {@link SavingsPlanService} - tik
 * {@code STATISTICS} (iš {@code stat_defaults}) ir {@code ASSUMPTION} (ilgalaikės modelio prielaidos,
 * pvz. grąža/infliacija, kai jų neprisiuntė vartotojas).
 *
 * @param field          laukas, su kuriuo reikšmė susijusi (snake_case, pvz. {@code monthly_expenses})
 * @param value          reikšmė
 * @param origin         {@code STATISTICS} arba {@code ASSUMPTION}
 * @param source         šaltinio aprašymas
 * @param sourceDate     duomenų data (ISO, {@code yyyy-MM-dd}); {@code null}, kai nėra realios datos
 * @param isIllustrative ar reikšmė iliustracinė (A2: {@code stat_defaults.is_illustrative}); {@code null},
 *                       kai neaktualu ({@code ASSUMPTION} kilmei - tai ne statistika)
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AssumptionEntry(
        String field,
        BigDecimal value,
        Origin origin,
        String source,
        String sourceDate,
        Boolean isIllustrative) {
}
