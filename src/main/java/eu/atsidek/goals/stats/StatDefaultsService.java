package eu.atsidek.goals.stats;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Parenka {@code stat_defaults} reikšmes pagal namų ūkio segmentą (members/workers) ir tikslo tipą, su
 * pagrįstu fallback'u, kai tikslaus segmento nėra (spec.md 4 skyrius). Validacija ir orkestravimas bendri
 * abiems kontroleriams ({@code /goals/stats/defaults} ir {@code /api/v1/goals/stats/defaults}), kaip
 * {@code eu.atsidek.goals.web.GoalsService} daro F1 PR #22 (žr. PR aprašą).
 */
@Service
public class StatDefaultsService {

    /**
     * PLAN.md K5 / F1 PR #22 ({@code eu.atsidek.goals.GoalsEngine}): grąžos ir infliacijos numatytosios –
     * ilgalaikės modelio prielaidos, NE {@code stat_defaults} įrašas, todėl kodo konstantos, kaip ir F1.
     * Kadangi F1 (eu.atsidek.goals paketas) šios šakos {@code main} bazėje dar nesujungtas, reikšmės čia
     * DUBLIUOJAMOS (negalima importuoti {@code GoalsEngine.DEFAULT_*}), kad šis PR kompiliuotųsi
     * savarankiškai. Po F1 merge abu turėtų naudoti vieną šaltinį – žr. PR aprašo pastabą apie laidą.
     * A0 sprendimas #2 (PLAN-taupymas-agentas.md): suderinta su {@code GoalsEngine.DEFAULT_ANNUAL_RETURN} - 0 %.
     */
    public static final BigDecimal DEFAULT_ANNUAL_RETURN = new BigDecimal("0");
    public static final BigDecimal DEFAULT_ANNUAL_INFLATION = new BigDecimal("2.5");
    private static final String ASSUMPTION_ORIGIN = "ASSUMPTION";
    private static final String ASSUMPTION_SOURCE = "Ilgalaikė modelio prielaida, ne statistinė prognozė";

    private static final String STATISTICS_ORIGIN = "STATISTICS";
    private static final Set<String> KNOWN_GOAL_TYPES = Set.of("CHILD", "CAR", "HOME_DOWN_PAYMENT", "OTHER");
    private static final String GENERAL_SEGMENT = "general";
    private static final Pattern HOUSEHOLD_SEGMENT = Pattern.compile("members=(\\d+);workers=(\\d+)");

    private final StatDefaultsStore store;

    public StatDefaultsService(StatDefaultsStore store) {
        this.store = store;
    }

    public StatDefaultsResponse resolve(Integer members, Integer workers, String region, String goalType) {
        validate(members, workers, goalType);

        StatDefaultValue income = resolveHousehold("income", members, workers);
        StatDefaultValue expenses = resolveHousehold("expenses", members, workers);
        StatDefaultValue typicalAmount = goalType == null ? null : resolveTypicalAmount(goalType);
        StatDefaultValue annualInflation = assumption(DEFAULT_ANNUAL_INFLATION);
        StatDefaultValue annualReturn = assumption(DEFAULT_ANNUAL_RETURN);

        return new StatDefaultsResponse(income, expenses, typicalAmount, annualInflation, annualReturn);
    }

    private void validate(Integer members, Integer workers, String goalType) {
        List<String> errors = new ArrayList<>();
        if (members != null && members < 1) {
            errors.add("members turi būti teigiamas skaičius");
        }
        if (workers != null && workers < 0) {
            errors.add("workers negali būti neigiamas");
        }
        if (members != null && workers != null && members >= 1 && workers >= 0 && workers > members) {
            errors.add("dirbančiųjų (workers) negali būti daugiau nei namų ūkio narių (members)");
        }
        if (goalType != null && !KNOWN_GOAL_TYPES.contains(goalType)) {
            errors.add("nežinomas tikslo tipas: " + goalType + " (leidžiama: CHILD, CAR, HOME_DOWN_PAYMENT, OTHER)");
        }
        if (!errors.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", errors));
        }
    }

    /**
     * Tikslus (members,workers) segmentas, jei yra; kitaip artimiausias pagal dydį; jei members/workers
     * visai nenurodyti – bendras ({@code "general"}) segmentas, apskaičiuotas kaip žinomų segmentų vidurkis.
     */
    private StatDefaultValue resolveHousehold(String key, Integer members, Integer workers) {
        List<StatDefaultsStore.Row> rows = store.findAllByKey(key);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Trūksta seed duomenų raktui: " + key);
        }
        if (members == null && workers == null) {
            return toValue(findSegment(rows, GENERAL_SEGMENT)
                    .orElseGet(() -> nearest(rows, null, null)
                            .orElseThrow(() -> new IllegalStateException("Nerasta tinkamo segmento raktui: " + key))));
        }
        if (members != null && workers != null) {
            Optional<StatDefaultsStore.Row> exact = findSegment(rows, segmentKey(members, workers));
            if (exact.isPresent()) {
                return toValue(exact.get());
            }
        }
        return toValue(nearest(rows, members, workers)
                .orElseThrow(() -> new IllegalStateException("Nerasta tinkamo segmento raktui: " + key)));
    }

    private StatDefaultValue resolveTypicalAmount(String goalType) {
        StatDefaultsStore.Row row = store.find("typical_amount", "goalType=" + goalType)
                .orElseThrow(() -> new IllegalStateException("Trūksta seed duomenų tikslo tipui: " + goalType));
        return toValue(row);
    }

    private static Optional<StatDefaultsStore.Row> findSegment(List<StatDefaultsStore.Row> rows, String segment) {
        return rows.stream().filter(r -> r.segment().equals(segment)).findFirst();
    }

    /**
     * Artimiausias žinomas (members,workers) segmentas Manheteno atstumu; nenurodyta dimensija (null)
     * atstumui neįtakoja ("nesvarbu"). Lygiomis – pirmas rastas (stabilia {@code findAllByKey} tvarka).
     */
    private static Optional<StatDefaultsStore.Row> nearest(List<StatDefaultsStore.Row> rows, Integer members, Integer workers) {
        StatDefaultsStore.Row best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (StatDefaultsStore.Row row : rows) {
            Matcher m = HOUSEHOLD_SEGMENT.matcher(row.segment());
            if (!m.matches()) {
                continue;
            }
            int rowMembers = Integer.parseInt(m.group(1));
            int rowWorkers = Integer.parseInt(m.group(2));
            int distance = (members == null ? 0 : Math.abs(rowMembers - members))
                    + (workers == null ? 0 : Math.abs(rowWorkers - workers));
            if (distance < bestDistance) {
                best = row;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    private static String segmentKey(int members, int workers) {
        return "members=" + members + ";workers=" + workers;
    }

    private static StatDefaultValue assumption(BigDecimal value) {
        return new StatDefaultValue(value, "PERCENT", ASSUMPTION_ORIGIN, ASSUMPTION_SOURCE, null, null, false);
    }

    private static StatDefaultValue toValue(StatDefaultsStore.Row row) {
        return new StatDefaultValue(row.value(), row.unit(), STATISTICS_ORIGIN, row.source(), row.sourceUrl(),
                row.asOf(), row.illustrative());
    }
}
