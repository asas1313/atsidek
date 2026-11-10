package eu.atsidek.goals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gryna taupymo tikslo skaičiavimo logika be jokių priklausomybių nuo Spring (žr. spec.md 3 skyrius).
 * Validacija (kritiniai laukai, ribos) atliekama anksčiau, {@code eu.atsidek.goals.web} sluoksnyje – šis
 * variklis tikisi, kad {@link GoalInput} jau turi visus pagal {@code mode} reikalingus laukus.
 */
public final class GoalsEngine {

    /** PLAN.md K5: ilgalaikės modelio prielaidos, ne LB ketvirtinė prognozė, todėl kodo konstantos, ne stat_defaults įrašas. */
    public static final BigDecimal DEFAULT_ANNUAL_RETURN = new BigDecimal("3");
    public static final BigDecimal DEFAULT_ANNUAL_INFLATION = new BigDecimal("2.5");

    private static final String ASSUMPTION_SOURCE = "Ilgalaikė modelio prielaida, ne statistinė prognozė";

    private static final int CALC_SCALE = 20;
    private static final int MAX_WHEN_MONTHS = 600;
    private static final int MAX_SERIES_POINTS = 24;
    private static final BigDecimal RATE_TO_MONTHLY_DIVISOR = BigDecimal.valueOf(1200); // %/100/12
    private static final BigDecimal SHARE_COMFORTABLE = new BigDecimal("0.20");
    private static final BigDecimal SHARE_TIGHT = new BigDecimal("0.50");

    public GoalResult calculate(GoalInput in) {
        return calculate(in, LocalDate.now());
    }

    /** {@code today} paimamas parametru (ne {@code LocalDate.now()} tiesiai), kad DATE/WHEN rezultatai būtų testuojami tiksliai. */
    public GoalResult calculate(GoalInput in, LocalDate today) {
        Map<String, Origin> origins = in.origins();
        Map<String, ResolvedValue> resolved = new LinkedHashMap<>();

        BigDecimal annualReturn = resolveAssumption("annualReturn", in.assumptions().annualReturn(),
                DEFAULT_ANNUAL_RETURN, origins, resolved);
        BigDecimal annualInflation = resolveAssumption("annualInflation", in.assumptions().annualInflation(),
                DEFAULT_ANNUAL_INFLATION, origins, resolved);
        putResolved(resolved, "amount", in.amount(), origins);
        putResolved(resolved, "income", in.household().income(), origins);
        putResolved(resolved, "expenses", in.household().expenses(), origins);

        BigDecimal rm = annualReturn.divide(RATE_TO_MONTHLY_DIVISOR, CALC_SCALE, RoundingMode.HALF_UP);
        BigDecimal free = nz(in.household().income()).subtract(nz(in.household().expenses()));

        return in.mode() == GoalMode.WHEN
                ? calculateWhen(in, rm, annualInflation, free, resolved, origins, today)
                : calculateByTerm(in, rm, annualInflation, free, resolved, origins, today);
    }

    private GoalResult calculateByTerm(GoalInput in, BigDecimal rm, BigDecimal annualInflation, BigDecimal free,
                                       Map<String, ResolvedValue> resolved, Map<String, Origin> origins,
                                       LocalDate today) {
        int n = monthsForMode(in, today);
        putTermResolved(in, origins, resolved);

        BigDecimal futureTarget = futureValue(in.amount(), annualInflation, n);
        BigDecimal monthly = requiredMonthly(futureTarget, rm, n);
        BigDecimal paid = monthly.multiply(BigDecimal.valueOf(n));
        BigDecimal accumulated = balance(monthly, rm, n);
        BigDecimal gain = accumulated.subtract(paid).max(BigDecimal.ZERO);
        BigDecimal share = share(monthly, free);
        Verdict verdict = verdict(free, share);
        List<SeriesPoint> series = buildSeries(monthly, rm, annualInflation, in.amount(), n, today);

        return new GoalResult(money(monthly), true, null, n, money(futureTarget), money(paid), money(gain),
                money(free), share, verdict, series, resolved);
    }

    private GoalResult calculateWhen(GoalInput in, BigDecimal rm, BigDecimal annualInflation, BigDecimal free,
                                     Map<String, ResolvedValue> resolved, Map<String, Origin> origins,
                                     LocalDate today) {
        putResolved(resolved, "monthlyContribution", in.monthlyContribution(), origins);
        BigDecimal monthly = in.monthlyContribution();
        BigDecimal share = share(monthly, free);
        Verdict verdict = verdict(free, share);

        Integer foundMonths = searchWhen(monthly, rm, annualInflation, in.amount());
        if (foundMonths == null) {
            return new GoalResult(money(monthly), false, null, null, null, null, null,
                    money(free), share, verdict, List.of(), resolved);
        }

        int n = foundMonths;
        BigDecimal futureTarget = futureValue(in.amount(), annualInflation, n);
        BigDecimal paid = monthly.multiply(BigDecimal.valueOf(n));
        BigDecimal accumulated = balance(monthly, rm, n);
        BigDecimal gain = accumulated.subtract(paid).max(BigDecimal.ZERO);
        LocalDate reachDate = today.plusMonths(n);
        List<SeriesPoint> series = buildSeries(monthly, rm, annualInflation, in.amount(), n, today);

        return new GoalResult(money(monthly), true, reachDate, n, money(futureTarget), money(paid), money(gain),
                money(free), share, verdict, series, resolved);
    }

    /** Ieško mažiausio k∈[1,600], kai B(P,k) ≥ FV(k); {@code null}, jei per 600 mėn. (50 m.) tikslas nepasiekiamas. */
    private static Integer searchWhen(BigDecimal monthly, BigDecimal rm, BigDecimal annualInflation, BigDecimal amount) {
        for (int k = 1; k <= MAX_WHEN_MONTHS; k++) {
            if (balance(monthly, rm, k).compareTo(futureValue(amount, annualInflation, k)) >= 0) {
                return k;
            }
        }
        return null;
    }

    private static int monthsForMode(GoalInput in, LocalDate today) {
        return switch (in.mode()) {
            case YEARS -> in.years() * 12;
            case DATE -> {
                int month = in.targetMonth() != null ? in.targetMonth() : 12;
                int n = (in.targetYear() - today.getYear()) * 12 + (month - today.getMonthValue());
                yield Math.max(12, n);
            }
            case CHILD_AGE -> Math.max(12, (in.childTargetAge() - in.childCurrentAge()) * 12);
            case WHEN -> throw new IllegalStateException("WHEN neturi fiksuoto termino, žr. calculateWhen");
        };
    }

    private static void putTermResolved(GoalInput in, Map<String, Origin> origins, Map<String, ResolvedValue> resolved) {
        switch (in.mode()) {
            case YEARS -> putResolved(resolved, "years", in.years(), origins);
            case DATE -> {
                putResolved(resolved, "targetYear", in.targetYear(), origins);
                putResolved(resolved, "targetMonth", in.targetMonth(), origins);
            }
            case CHILD_AGE -> {
                putResolved(resolved, "childCurrentAge", in.childCurrentAge(), origins);
                putResolved(resolved, "childTargetAge", in.childTargetAge(), origins);
            }
            case WHEN -> { }
        }
    }

    /**
     * Tikslo suma su infliacija po k mėnesių: {@code S × (1 + i/100)^(k/12)}. Rodiklis trupmeninis (metais),
     * todėl BigDecimal.pow(int) netinka – augimo koeficientas skaičiuojamas double, o pati pinigų suma
     * (ir galutinis apvalinimas iki centų) lieka BigDecimal.
     */
    static BigDecimal futureValue(BigDecimal amount, BigDecimal annualInflationPct, int months) {
        double growth = Math.pow(1 + annualInflationPct.doubleValue() / 100.0, months / 12.0);
        return amount.multiply(BigDecimal.valueOf(growth));
    }

    /**
     * Sukaupta suma po k mėnesių mokant P per mėnesį su mėnesine grąža rm; rm=0 → tiesiog P×k.
     * Viešas (ne package-private), kad {@code eu.atsidek.goals.tracking.SavingsGoalService} galėtų
     * pakartotinai naudoti tą pačią formulę numatytai (planinei) sukauptai sumai skaičiuoti
     * (PLAN.md taupymo-tikslai-atskyrimas, §6 task 9) – nauja skaičiavimo logika nekuriama iš naujo.
     */
    public static BigDecimal balance(BigDecimal monthly, BigDecimal rm, int k) {
        if (rm.signum() == 0) {
            return monthly.multiply(BigDecimal.valueOf(k));
        }
        BigDecimal factor = BigDecimal.ONE.add(rm).pow(k).subtract(BigDecimal.ONE);
        return monthly.multiply(factor).divide(rm, CALC_SCALE, RoundingMode.HALF_UP);
    }

    /** Reikalinga mėnesio įmoka, kad per n mėnesių sukauptum futureTarget; rm=0 → futureTarget/n. */
    static BigDecimal requiredMonthly(BigDecimal futureTarget, BigDecimal rm, int n) {
        if (rm.signum() == 0) {
            return futureTarget.divide(BigDecimal.valueOf(n), CALC_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal factor = BigDecimal.ONE.add(rm).pow(n).subtract(BigDecimal.ONE);
        return futureTarget.multiply(rm).divide(factor, CALC_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal share(BigDecimal monthly, BigDecimal free) {
        return free.signum() > 0
                ? monthly.divide(free, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(4);
    }

    private static Verdict verdict(BigDecimal free, BigDecimal share) {
        if (free.signum() <= 0) {
            return Verdict.NO_FREE_CASH;
        }
        if (share.compareTo(SHARE_COMFORTABLE) <= 0) {
            return Verdict.COMFORTABLE;
        }
        if (share.compareTo(SHARE_TIGHT) <= 0) {
            return Verdict.TIGHT;
        }
        return Verdict.UNREALISTIC;
    }

    /** Kasmetiniai taškai grafikui, iki {@value #MAX_SERIES_POINTS}; ilgesnis terminas – praretintas, bet paskutinis (tikslo) taškas visada įtraukiamas. */
    private static List<SeriesPoint> buildSeries(BigDecimal monthly, BigDecimal rm, BigDecimal annualInflation,
                                                  BigDecimal amount, int n, LocalDate today) {
        int totalYears = (n + 11) / 12; // ceil(n / 12)
        if (totalYears <= 0) {
            return List.of();
        }
        int step = totalYears <= MAX_SERIES_POINTS ? 1 : (int) Math.ceil(totalYears / (double) MAX_SERIES_POINTS);
        int baseYear = today.getYear();
        List<SeriesPoint> series = new ArrayList<>();
        for (int year = step; year < totalYears; year += step) {
            int k = Math.min(year * 12, n);
            series.add(point(baseYear + year, monthly, rm, annualInflation, amount, k));
        }
        series.add(point(baseYear + totalYears, monthly, rm, annualInflation, amount, n));
        return series;
    }

    private static SeriesPoint point(int year, BigDecimal monthly, BigDecimal rm, BigDecimal annualInflation,
                                     BigDecimal amount, int k) {
        return new SeriesPoint(year, money(balance(monthly, rm, k)), money(futureValue(amount, annualInflation, k)));
    }

    private static BigDecimal resolveAssumption(String key, BigDecimal value, BigDecimal fallback,
                                                 Map<String, Origin> origins, Map<String, ResolvedValue> resolved) {
        if (value != null) {
            resolved.put(key, new ResolvedValue(money(value), origins.getOrDefault(key, Origin.USER), null, null));
            return value;
        }
        resolved.put(key, new ResolvedValue(money(fallback), Origin.ASSUMPTION, ASSUMPTION_SOURCE, null));
        return fallback;
    }

    private static void putResolved(Map<String, ResolvedValue> resolved, String key, BigDecimal value,
                                     Map<String, Origin> origins) {
        if (value == null) {
            return;
        }
        resolved.put(key, new ResolvedValue(money(value), origins.getOrDefault(key, Origin.USER), null, null));
    }

    private static void putResolved(Map<String, ResolvedValue> resolved, String key, Integer value,
                                     Map<String, Origin> origins) {
        if (value == null) {
            return;
        }
        resolved.put(key, new ResolvedValue(value, origins.getOrDefault(key, Origin.USER), null, null));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
