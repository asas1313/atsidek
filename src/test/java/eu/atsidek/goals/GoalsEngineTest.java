package eu.atsidek.goals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Formulės pagal spec.md 3 skyrių (rm, FV, B, P, n pagal režimą, WHEN paieška, verdict). Rankiniu būdu
 * tiksliai patikrinti pavyzdžiai (ne makete matomi suapvalinti skaičiai) paskaičiuoti atskirai pagal tas
 * pačias formules (žr. PR aprašą) ir perkelti žemiau kaip assertEquals.
 */
class GoalsEngineTest {

    private final GoalsEngine engine = new GoalsEngine();
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static Household household(String income, String expenses) {
        return new Household(null, null, income == null ? null : d(income), expenses == null ? null : d(expenses), null);
    }

    // ---- po testą kiekvienam režimui, su rankiniu būdu tiksliai patikrintomis reikšmėmis ----

    @Test
    void yearsMode_manualExample() {
        // amount=5000, years=1 (n=12 – kraštutinis minimalus terminas), r=4%, i=2%, income=1000, expenses=900
        GoalInput in = new GoalInput(GoalType.OTHER, GoalMode.YEARS, d("5000"), 1, null, null, null, null, null,
                household("1000", "900"), new Assumptions(d("4"), d("2")), Map.of());
        GoalResult r = engine.calculate(in, TODAY);

        assertEquals(12, r.months());
        assertEquals(d("5100.00"), r.futureTarget());
        assertEquals(d("417.26"), r.monthly());
        assertEquals(d("5007.17"), r.paid());
        assertEquals(d("92.83"), r.gain());
        assertEquals(d("100.00"), r.freeCash());
        assertEquals(d("4.1726"), r.share());
        assertEquals(Verdict.UNREALISTIC, r.verdict());
        assertTrue(r.reachable());
        assertNull(r.reachDate());
    }

    @Test
    void dateMode_manualExample() {
        // amount=15000, today 2026-11-10 -> 2028-05 (n=18), r=3%, i=2.5%, income=2500, expenses=1700
        GoalInput in = new GoalInput(GoalType.CAR, GoalMode.DATE, d("15000"), null, 2028, 5, null, null, null,
                household("2500", "1700"), new Assumptions(d("3"), d("2.5")), Map.of());
        GoalResult r = engine.calculate(in, TODAY);

        assertEquals(18, r.months());
        assertEquals(d("15566.00"), r.futureTarget());
        assertEquals(d("846.55"), r.monthly());
        assertEquals(d("15237.84"), r.paid());
        assertEquals(d("328.16"), r.gain());
        assertEquals(d("800.00"), r.freeCash());
        assertEquals(d("1.0582"), r.share());
        assertEquals(Verdict.UNREALISTIC, r.verdict());
    }

    @Test
    void dateMode_clampsToMinimum12Months() {
        // 2026-11-10 -> 2027-01 būtų tik 2 mėn., turi būti pakelta iki minimumo 12
        GoalInput in = new GoalInput(GoalType.OTHER, GoalMode.DATE, d("1200"), null, 2027, 1, null, null, null,
                household("1000", "500"), new Assumptions(d("0"), d("0")), Map.of());
        GoalResult r = engine.calculate(in, TODAY);

        assertEquals(12, r.months());
        assertEquals(d("100.00"), r.monthly()); // r=0,i=0 -> tiesiog 1200/12
    }

    @Test
    void childAgeMode_manualExample() {
        // maketo orientacinis pavyzdys (docs/specs/taupymo-tikslai/mockups), bet reikšmės perskaičiuotos tiksliai,
        // ne nukopijuotos: amount=20000, vaikas 4->18 (n=168), r=3%, i=2.5%, income=3200, expenses=1900
        GoalInput in = new GoalInput(GoalType.CHILD, GoalMode.CHILD_AGE, d("20000"), null, null, null, 4, 18, null,
                household("3200", "1900"), new Assumptions(d("3"), d("2.5")), Map.of());
        GoalResult r = engine.calculate(in, TODAY);

        assertEquals(168, r.months());
        assertEquals(d("28259.48"), r.futureTarget());
        assertEquals(d("135.56"), r.monthly());
        assertEquals(d("22773.98"), r.paid());
        assertEquals(d("5485.50"), r.gain());
        assertEquals(d("1300.00"), r.freeCash());
        assertEquals(d("0.1043"), r.share());
        assertEquals(Verdict.COMFORTABLE, r.verdict());
    }

    @Test
    void childAgeMode_targetAgeMustExceedCurrentAge_producesNegativeMonths() {
        // Variklis pats neturvalidavo (tai GoalsService darbas); čia tik patikrinama formulė n=(target-current)*12,
        // apsaugota minimumu 12, kad net klaidingai praėjęs reiktas neužstrigtų neigiamu/nuliniu terminu.
        GoalInput in = new GoalInput(GoalType.CHILD, GoalMode.CHILD_AGE, d("1000"), null, null, null, 10, 5, null,
                household("1000", "500"), new Assumptions(d("0"), d("0")), Map.of());
        GoalResult r = engine.calculate(in, TODAY);
        assertEquals(12, r.months()); // (5-10)*12 = -60 -> apsaugota minimumu 12
    }

    @Test
    void whenMode_findsReachableMonth() {
        // amount=10000, monthly=200, r=3%, i=2.5% -> smallest k su B(P,k) >= FV(k) yra 53
        GoalInput in = new GoalInput(GoalType.OTHER, GoalMode.WHEN, d("10000"), null, null, null, null, null,
                d("200"), household("3000", "2000"), new Assumptions(d("3"), d("2.5")), Map.of());
        GoalResult r = engine.calculate(in, TODAY);

        assertTrue(r.reachable());
        assertEquals(53, r.months());
        assertEquals(TODAY.plusMonths(53), r.reachDate());
        assertEquals(d("200.00"), r.monthly());
        assertEquals(d("11152.28"), r.futureTarget());
        assertEquals(d("10600.00"), r.paid());
        assertEquals(d("719.22"), r.gain());
        assertEquals(d("0.2000"), r.share()); // 200/1000 free - riba, vis dar COMFORTABLE (<=0.20)
        assertEquals(Verdict.COMFORTABLE, r.verdict());
    }

    @Test
    void whenMode_unreachableWithin600MonthsReturnsReachableFalse() {
        // amount=1 000 000, monthly=10 -> per 600 mėn. (50 m.) nepasiekiama
        GoalInput in = new GoalInput(GoalType.OTHER, GoalMode.WHEN, d("1000000"), null, null, null, null, null,
                d("10"), household("5000", "3000"), new Assumptions(d("3"), d("2.5")), Map.of());
        GoalResult r = engine.calculate(in, TODAY);

        assertFalse(r.reachable());
        assertNull(r.months());
        assertNull(r.reachDate());
        assertNull(r.futureTarget());
        assertNull(r.paid());
        assertNull(r.gain());
        assertTrue(r.series().isEmpty());
        // freeCash/share/verdict nepriklauso nuo pasiekiamumo
        assertEquals(d("2000.00"), r.freeCash());
        assertEquals(Verdict.COMFORTABLE, r.verdict());
    }

    // ---- kraštutiniai atvejai ----

    @Test
    void zeroReturnAndZeroInflation() {
        // r=0, i=0: amount=12000, years=5 (n=60) -> monthly = amount/n tiesiogiai, be grąžos/infliacijos
        GoalInput in = new GoalInput(GoalType.OTHER, GoalMode.YEARS, d("12000"), 5, null, null, null, null, null,
                household("2000", "1500"), new Assumptions(d("0"), d("0")), Map.of());
        GoalResult r = engine.calculate(in, TODAY);

        assertEquals(60, r.months());
        assertEquals(d("12000.00"), r.futureTarget());
        assertEquals(d("200.00"), r.monthly());
        assertEquals(d("12000.00"), r.paid());
        assertEquals(d("0.00"), r.gain());
        assertEquals(d("500.00"), r.freeCash());
        assertEquals(d("0.4000"), r.share());
        assertEquals(Verdict.TIGHT, r.verdict());
    }

    @Test
    void freeCashZeroOrNegativeYieldsNoFreeCashRegardlessOfShare() {
        GoalInput zeroFree = new GoalInput(GoalType.OTHER, GoalMode.YEARS, d("1200"), 1, null, null, null, null, null,
                household("1000", "1000"), new Assumptions(d("0"), d("0")), Map.of());
        assertEquals(Verdict.NO_FREE_CASH, engine.calculate(zeroFree, TODAY).verdict());

        GoalInput negativeFree = new GoalInput(GoalType.OTHER, GoalMode.YEARS, d("1200"), 1, null, null, null, null, null,
                household("900", "1000"), new Assumptions(d("0"), d("0")), Map.of());
        GoalResult r = engine.calculate(negativeFree, TODAY);
        assertEquals(Verdict.NO_FREE_CASH, r.verdict());
        assertEquals(d("-100.00"), r.freeCash());
        assertEquals(d("0.0000"), r.share()); // neigiamam free share nedalinamas, laikomas 0
    }

    // ---- kilmė (origin) ----

    @Test
    void missingAssumptionsResolveToDefaultsWithAssumptionOrigin() {
        GoalInput in = new GoalInput(GoalType.OTHER, GoalMode.YEARS, d("1000"), 2, null, null, null, null, null,
                household(null, null), new Assumptions(null, null), Map.of());
        GoalResult r = engine.calculate(in, TODAY);

        ResolvedValue returnValue = r.resolvedInputs().get("annualReturn");
        ResolvedValue inflationValue = r.resolvedInputs().get("annualInflation");
        assertEquals(Origin.ASSUMPTION, returnValue.origin());
        assertEquals(d("0.00"), returnValue.value()); // A0 sprendimas #2: numatytoji grąža 0 %, ne 3 %
        assertNull(returnValue.asOf());
        assertEquals(Origin.ASSUMPTION, inflationValue.origin());
        assertEquals(d("2.50"), inflationValue.value());
        // income/expenses nebuvo pateikti ir F2 statistikos šaltinio čia nėra - nerodomi kaip išspręsti
        assertFalse(r.resolvedInputs().containsKey("income"));
        assertFalse(r.resolvedInputs().containsKey("expenses"));
    }

    @Test
    void missingAnnualReturnAppliesZeroPercentInTheMonthlyFormula() {
        // A0 sprendimas #2: numatytoji grąža yra 0 %, taigi praleista grąža neturi sumažinti reikalingos
        // mėnesinės įmokos per kaupimo augimą (rm=0) - tik infliacijos korekcija (i=2.5 % numatytoji) veikia.
        GoalInput withDefaultReturn = new GoalInput(GoalType.OTHER, GoalMode.YEARS, d("1200"), 1, null, null, null,
                null, null, household("1000", "900"), new Assumptions(null, null), Map.of());
        GoalInput withExplicitZeroReturn = new GoalInput(GoalType.OTHER, GoalMode.YEARS, d("1200"), 1, null, null,
                null, null, null, household("1000", "900"), new Assumptions(d("0"), null), Map.of());

        GoalResult withDefault = engine.calculate(withDefaultReturn, TODAY);
        GoalResult withExplicitZero = engine.calculate(withExplicitZeroReturn, TODAY);

        assertEquals(withExplicitZero.monthly(), withDefault.monthly());
        assertEquals(d("0.00"), GoalsEngine.DEFAULT_ANNUAL_RETURN.setScale(2));
    }

    @Test
    void explicitValuesDefaultToUserOriginUnlessDeclaredOtherwise() {
        Map<String, Origin> origins = Map.of("amount", Origin.AI, "expenses", Origin.AI);
        GoalInput in = new GoalInput(GoalType.OTHER, GoalMode.YEARS, d("1000"), 2, null, null, null, null, null,
                household("2000", "1500"), new Assumptions(d("3"), d("2.5")), origins);
        GoalResult r = engine.calculate(in, TODAY);

        assertEquals(Origin.AI, r.resolvedInputs().get("amount").origin());
        assertEquals(Origin.AI, r.resolvedInputs().get("expenses").origin());
        assertEquals(Origin.USER, r.resolvedInputs().get("income").origin()); // nedeklaruota -> USER
        assertEquals(Origin.USER, r.resolvedInputs().get("annualReturn").origin()); // pateikta reikšmė, nedeklaruota -> USER
    }

    // ---- series praretinimas ----

    @Test
    void seriesCoversEveryYearForShortTerm() {
        GoalInput in = new GoalInput(GoalType.OTHER, GoalMode.YEARS, d("3600"), 3, null, null, null, null, null,
                household("1000", "500"), new Assumptions(d("0"), d("0")), Map.of());
        GoalResult r = engine.calculate(in, TODAY);

        assertEquals(3, r.series().size());
        SeriesPoint last = r.series().get(r.series().size() - 1);
        assertEquals(r.futureTarget(), last.target());
        assertEquals(r.paid(), last.balance());
    }

    @Test
    void seriesIsThinnedTo24PointsForLongTerm() {
        GoalInput in = new GoalInput(GoalType.OTHER, GoalMode.YEARS, d("48000"), 40, null, null, null, null, null,
                household("2000", "1000"), new Assumptions(d("3"), d("2.5")), Map.of());
        GoalResult r = engine.calculate(in, TODAY);

        assertTrue(r.series().size() <= 24, "series neturi viršyti 24 taškų");
        SeriesPoint last = r.series().get(r.series().size() - 1);
        assertEquals(r.futureTarget(), last.target());
    }
}
