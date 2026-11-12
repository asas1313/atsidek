package eu.atsidek.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Scenarijai #1–7, #10 sutikrinti su oficialia VMI/Sodros individualios veiklos skaičiuokle 2026 m.
 * (žr. VERIFIKAVIMO-REZULTATAI.md). Scenarijai su kitomis pajamomis (#8, #9) apskaičiuoti pagal VMI DUK,
 * oficiali skaičiuoklė jų nepalaiko.
 */
class TaxEngineTest {

    private final TaxEngine engine = TaxEngine.forYear2026();

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private TaxInput flat(String revenue, String other, boolean pillar2) {
        return new TaxInput(d(revenue), ExpenseMethod.FLAT_30, BigDecimal.ZERO, d(other), pillar2);
    }

    private void assertScenario(TaxResult r, String taxable, String gpm, String vsd, String psd, String total) {
        assertEquals(d(taxable), r.taxableIncome(), "apmokestinamosios");
        assertEquals(d(gpm), r.gpm(), "GPM");
        assertEquals(d(vsd), r.vsd(), "VSD");
        assertEquals(d(psd), r.psd(), "PSD");
        assertEquals(d(total), r.total(), "iš viso");
    }

    @Test
    void official1() {
        assertScenario(engine.calculate(flat("12000", "0", false)), "8400.00", "420.00", "946.51", "965.76", "2332.27");
    }

    @Test
    void official2() {
        assertScenario(engine.calculate(flat("20000", "0", false)), "14000.00", "700.00", "1577.52", "965.76", "3243.28");
    }

    @Test
    void official3() {
        assertScenario(engine.calculate(flat("30000", "0", false)), "21000.00", "1190.00", "2366.28", "1319.22", "4875.50");
    }

    @Test
    void official3NetMonthlyIncomeIsRevenueMinusTotalDividedByTwelve() {
        // (30000 - 4875.50) / 12 = 25124.50 / 12 = 2093.7083(3) -> HALF_UP 2093.71
        TaxResult r = engine.calculate(flat("30000", "0", false));
        assertEquals(d("2093.71"), r.netMonthlyIncome());
    }

    @Test
    void official4() {
        assertScenario(engine.calculate(flat("40000", "0", false)), "28000.00", "2893.33", "3155.04", "1758.96", "7807.33");
    }

    @Test
    void official5() {
        assertScenario(engine.calculate(flat("60000", "0", false)), "42000.00", "8260.00", "4732.56", "2638.44", "15631.00");
    }

    @Test
    void official6ActualExpensesDeductSodra() {
        TaxInput in = new TaxInput(d("60000"), ExpenseMethod.ACTUAL, d("15000"), BigDecimal.ZERO, false);
        TaxResult r = engine.calculate(in);
        assertEquals(d("37102.50"), r.gpmBase());
        assertScenario(r, "45000.00", "6085.43", "5070.60", "2826.90", "13982.93");
    }

    @Test
    void official6NetMonthlyIncomeIsRevenueMinusTotalDividedByTwelveForActualMethodToo() {
        // (60000 - 13982.93) / 12 = 46017.07 / 12 = 3834.7558(3) -> HALF_UP 3834.76
        TaxInput in = new TaxInput(d("60000"), ExpenseMethod.ACTUAL, d("15000"), BigDecimal.ZERO, false);
        TaxResult r = engine.calculate(in);
        assertEquals(d("3834.76"), r.netMonthlyIncome());
    }

    @Test
    void official7() {
        assertScenario(engine.calculate(flat("100000", "0", false)), "70000.00", "14000.00", "7887.60", "4397.40", "26285.00");
    }

    @Test
    void official10PensionAccumulation() {
        TaxResult r = engine.calculate(flat("40000", "0", true));
        assertEquals(d("756.00"), r.pensionAccumulation());
        assertEquals(d("2893.33"), r.gpm());
        assertEquals(d("8563.33"), r.total());
    }

    @Test
    void salaryBelowBracketAddsNoExtraGpm() {
        TaxResult r = engine.calculate(flat("30000", "40000", false));
        assertEquals(d("1190.00"), r.gpm());
        assertEquals(d("0.00"), r.extraGpm());
    }

    @Test
    void ivIncomeUsesUpBracketForSalary() {
        TaxResult r = engine.calculate(flat("60000", "60000", false));
        assertEquals(d("8260.00"), r.gpm());
        assertEquals(d("938.13"), r.extraGpm());
    }

    @Test
    void sodraCeilingIsCapped() {
        TaxResult r = engine.calculate(flat("300000", "0", false));
        assertTrue(r.sodraCeilingHit());
        assertEquals(d("99422.45"), r.sodraBase());
    }

    @Test
    void gpmIsContinuousAtBandEdges() {
        for (String edge : new String[] {"20000", "42500"}) {
            BigDecimal below = engine.ivGpm(d(edge));
            BigDecimal above = engine.ivGpm(d(edge).add(d("0.01")));
            assertTrue(above.subtract(below).abs().compareTo(d("0.01")) < 0, "šuolis ties " + edge);
        }
        assertTrue(d("8500").subtract(engine.ivGpm(d("42500"))).abs().compareTo(d("0.001")) < 0);
    }

    @Test
    void zeroIncomeCostsNothing() {
        assertEquals(d("0.00"), engine.calculate(flat("0", "0", false)).total());
    }

    @Test
    void comparisonPicksCheaperMethod() {
        TaxInput in = new TaxInput(d("50000"), ExpenseMethod.FLAT_30, d("40000"), BigDecimal.ZERO, false);
        TaxComparison c = engine.compare(in);
        assertEquals(ExpenseMethod.ACTUAL, c.better().method());

        TaxComparison c2 = engine.compare(new TaxInput(d("50000"), null, d("1000"), BigDecimal.ZERO, false));
        assertEquals(ExpenseMethod.FLAT_30, c2.better().method());
        assertFalse(c2.better() == c2.actual());
    }

    @Test
    void actualExpensesCannotExceedRevenue() {
        TaxInput in = new TaxInput(d("10000"), ExpenseMethod.ACTUAL, d("99999"), BigDecimal.ZERO, false);
        assertEquals(d("0.00"), engine.calculate(in).taxableIncome());
    }

    @Test
    void minimumPsdOnlyForSelectedMonths() {
        // 12 000 €: PSD nuo pajamų (≈ 527 €) mažesnė už minimumą, todėl minimumas lemia sumą
        TaxResult all = engine.calculate(flat("12000", "0", false));
        TaxResult sevenMonths = engine.calculate(
                new TaxInput(d("12000"), ExpenseMethod.FLAT_30, BigDecimal.ZERO, BigDecimal.ZERO, false, 7));
        assertEquals(d("563.36"), sevenMonths.psd());                  // 7 × 80,48
        assertTrue(sevenMonths.total().compareTo(all.total()) < 0);
        // nulis mėnesių: lieka tik PSD nuo pajamų (6,98 % × 90 % × 8 400)
        TaxResult none = engine.calculate(
                new TaxInput(d("12000"), ExpenseMethod.FLAT_30, BigDecimal.ZERO, BigDecimal.ZERO, false, 0));
        assertEquals(d("527.69"), none.psd());
    }
}
