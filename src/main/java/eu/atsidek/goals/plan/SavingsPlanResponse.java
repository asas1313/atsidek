package eu.atsidek.goals.plan;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import eu.atsidek.entitlement.Feature;
import java.util.List;

/**
 * {@code POST /api/v1/savings/plan} atsakymas (PLAN-taupymas-agentas.md A3).
 * <p>
 * {@code tier} ir {@code premium} rakto buvimas yra DVI ATSKIROS ašys, sąmoningai nesumaišytos (žr.
 * {@link SavingsPlanService}): {@code tier} priklauso nuo TURIMŲ {@code Feature} teisių (beta
 * vėliava šiuo metu duoda visas kiekvienam prisijungusiam), o {@code premium} rakto buvimas priklauso
 * TIK nuo to, ar skambinantysis prisijungęs (anonimui - niekada, nepriklausomai nuo ateities, kai beta
 * baigsis ir atsiras prisijungęs-bet-FREE atvejis).
 *
 * @param tier            FREE arba PREMIUM, pagal turimas {@code Feature} teises
 * @param core            nemokama dalis - visada grąžinama
 * @param lockedFeatures  {@code Feature} reikšmės, kurių šis tier'as neturi (tuščias, kai PREMIUM)
 * @param premium         premium dalis; {@code null} (rakto NĖRA JSON'e) anoniminiam skambinančiajam
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SavingsPlanResponse(
        Tier tier,
        Core core,
        List<Feature> lockedFeatures,
        @JsonInclude(JsonInclude.Include.NON_NULL) Premium premium) {
}
