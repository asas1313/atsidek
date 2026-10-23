package lt.atsidek.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Skaičiai čia suskaičiuoti ranka pagal SPEC.md formules. Jie NĖRA sutikrinti su VMI ir Sodros
 * skaičiuoklėmis – tai dar atviras paleidimo punktas.
 */
class TaxEngineTest {

    private final TaxEngine engine = TaxEngine.forYear2026();

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private TaxInput flat(String revenue, String other, boolean pillar2) {
        return new TaxInput(d(revenue), ExpenseMethod.FLAT_30, BigDecimal.ZERO, d(other), pillar2);
    }

    @Test
    void lowIncomeGetsFivePercentGpm() {
        TaxResult r = engine.calculate(flat("30000", "0", false));
        assertEquals(d("21000.00"), r.taxableIncome());
        assertEquals(d("18900.00"), r.sodraBase());
        assertEquals(d("2366.28"), r.vsd());
        assertEquals(d("1319.22"), r.psd());
        assertEquals(d("17314.50"), r.gpmBase());
        assertEquals(d("865.73"), r.gpm());
        assertEquals(d("4551.23"), r.total());
        assertEquals(d("379.27"), r.monthlyReserve());
    }

    @Test
    void minimumPsdAppliesForTinyIncome() {
        TaxResult r = engine.calculate(flat("1000", "0", false));
        assertEquals(d("965.76"), r.psd());
    }

    @Test
    void pensionAccumulationAddsThreePercent() {
        TaxResult without = engine.calculate(flat("30000", "0", false));
        TaxResult with = engine.calculate(flat("30000", "0", true));
        assertEquals(d("567.00"), with.pensionAccumulation());
        assertTrue(with.sodraTotal().compareTo(without.sodraTotal()) > 0);
    }

    @Test
    void highIncomeUsesProgressiveRateAboveCap() {
        TaxResult r = engine.calculate(flat("100000", "0", false));
        assertEquals(d("12285.00"), r.sodraTotal());
        assertEquals(d("57715.00"), r.gpmBase());
        assertEquals(d("11543.00"), r.gpm());
        assertEquals(d("23828.00"), r.total());
    }

    @Test
    void otherIncomePushesIvIntoHigherBracket() {
        BigDecimal alone = engine.calculate(flat("100000", "0", false)).gpm();
        BigDecimal withSalary = engine.calculate(flat("100000", "60000", false)).gpm();
        assertTrue(withSalary.compareTo(alone) > 0);
    }

    @Test
    void otherIncomeRaisesGpmEvenForModestIvIncome() {
        BigDecimal alone = engine.calculate(flat("30000", "0", false)).gpm();
        BigDecimal withSalary = engine.calculate(flat("30000", "40000", false)).gpm();
        assertTrue(withSalary.compareTo(alone) > 0);
    }

    @Test
    void sodraCeilingIsCapped() {
        TaxResult r = engine.calculate(flat("300000", "0", false));
        assertTrue(r.sodraCeilingHit());
        assertEquals(d("99422.45"), r.sodraBase());
    }

    @Test
    void gpmIsContinuousAtBandEdges() {
        for (String edge : new String[] {"20000", "35000", "42500"}) {
            BigDecimal below = engine.ivGpm(d(edge), BigDecimal.ZERO);
            BigDecimal above = engine.ivGpm(d(edge).add(d("0.01")), BigDecimal.ZERO);
            assertTrue(above.subtract(below).abs().compareTo(d("0.01")) < 0, "šuolis ties " + edge);
        }
        assertEquals(0, d("8500").compareTo(engine.ivGpm(d("42500"), BigDecimal.ZERO)));
    }

    @Test
    void zeroIncomeCostsNothing() {
        TaxResult r = engine.calculate(flat("0", "0", false));
        assertEquals(d("0.00"), r.total());
    }

    @Test
    void comparisonPicksCheaperMethod() {
        TaxInput in = new TaxInput(d("50000"), ExpenseMethod.FLAT_30, d("40000"), BigDecimal.ZERO, false);
        TaxComparison c = engine.compare(in);
        assertEquals(ExpenseMethod.ACTUAL, c.better().method());
        assertTrue(c.actual().total().compareTo(c.flat().total()) < 0);

        TaxComparison c2 = engine.compare(new TaxInput(d("50000"), null, d("1000"), BigDecimal.ZERO, false));
        assertEquals(ExpenseMethod.FLAT_30, c2.better().method());
        assertFalse(c2.better() == c2.actual());
    }

    @Test
    void actualExpensesCannotExceedRevenue() {
        TaxInput in = new TaxInput(d("10000"), ExpenseMethod.ACTUAL, d("99999"), BigDecimal.ZERO, false);
        assertEquals(d("0.00"), engine.calculate(in).taxableIncome());
    }
}
