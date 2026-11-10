package eu.atsidek.goals;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Taupymo tikslo apskaičiavimo užklausos laukai (žr. spec.md 4 skyrius). Pagal {@code mode} naudojami tik
 * atitinkami termino laukai, kiti gali likti {@code null}.
 *
 * @param goalType            tikslo tipas
 * @param mode                termino apibrėžimo būdas
 * @param amount              tikslo suma šiandienos kainomis
 * @param years               metų skaičius (mode = YEARS)
 * @param targetYear          tikslo metai (mode = DATE)
 * @param targetMonth         tikslo mėnuo 1–12; nebūtinas, numatytasis – gruodis (mode = DATE)
 * @param childCurrentAge     vaiko dabartinis amžius (mode = CHILD_AGE)
 * @param childTargetAge      vaiko tikslinis amžius, turi būti didesnis už dabartinį (mode = CHILD_AGE)
 * @param monthlyContribution mėnesio įmoka (mode = WHEN)
 * @param household           namų ūkio duomenys laisviems pinigams skaičiuoti
 * @param assumptions         grąžos ir infliacijos prielaidos; {@code null} laukai užpildomi numatytosiomis (ASSUMPTION)
 * @param origins             kiekvieno lauko kilmė, jei žinoma (raktas – JSON lauko pavadinimas, pvz. {@code "amount"})
 */
public record GoalInput(
        GoalType goalType,
        GoalMode mode,
        BigDecimal amount,
        Integer years,
        Integer targetYear,
        Integer targetMonth,
        Integer childCurrentAge,
        Integer childTargetAge,
        BigDecimal monthlyContribution,
        Household household,
        Assumptions assumptions,
        Map<String, Origin> origins) {

    public GoalInput {
        if (household == null) {
            household = new Household(null, null, null, null, null);
        }
        if (assumptions == null) {
            assumptions = new Assumptions(null, null);
        }
        if (origins == null) {
            origins = Map.of();
        }
    }
}
