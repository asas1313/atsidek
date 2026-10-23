package lt.atsidek.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Gryna skaičiavimo logika be jokių priklausomybių nuo Spring. */
public final class TaxEngine {

    private static final BigDecimal MONTHS = BigDecimal.valueOf(12);

    private final TaxRules rules;

    public TaxEngine(TaxRules rules) {
        this.rules = rules;
    }

    public static TaxEngine forYear2026() {
        return new TaxEngine(TaxRules.RULES_2026);
    }

    public TaxComparison compare(TaxInput in) {
        TaxResult flat = calculate(in.withMethod(ExpenseMethod.FLAT_30));
        TaxResult actual = calculate(in.withMethod(ExpenseMethod.ACTUAL));
        TaxResult better = actual.total().compareTo(flat.total()) < 0 ? actual : flat;
        return new TaxComparison(flat, actual, better);
    }

    public TaxResult calculate(TaxInput in) {
        BigDecimal revenue = in.ivRevenue();
        BigDecimal expenses = in.method() == ExpenseMethod.FLAT_30
                ? revenue.multiply(rules.expenseFlatShare())
                : in.actualExpenses().min(revenue);
        BigDecimal taxable = revenue.subtract(expenses).max(BigDecimal.ZERO);

        // Sodra
        BigDecimal ceiling = rules.sodraCeilingVdu().multiply(rules.vdu());
        BigDecimal rawBase = taxable.multiply(rules.sodraBaseShare());
        boolean ceilingHit = rawBase.compareTo(ceiling) > 0;
        BigDecimal base = rawBase.min(ceiling);
        BigDecimal vsd = base.multiply(rules.vsdRate());
        BigDecimal psdMin = rules.minMonthlyPsd().multiply(MONTHS);
        BigDecimal psd = revenue.signum() == 0
                ? BigDecimal.ZERO
                : base.multiply(rules.psdRate()).max(psdMin);
        BigDecimal pension = in.pensionAccumulation()
                ? base.multiply(rules.pensionAccumulationRate())
                : BigDecimal.ZERO;

        // GPM
        BigDecimal deduction = rules.contributionsDeductible() ? vsd.add(psd).add(pension) : BigDecimal.ZERO;
        BigDecimal gpmBase = taxable.subtract(deduction).max(BigDecimal.ZERO);
        BigDecimal gpm = ivGpm(gpmBase, in.otherIncome());

        BigDecimal total = gpm.add(vsd).add(psd).add(pension);
        BigDecimal effective = taxable.signum() == 0
                ? BigDecimal.ZERO
                : total.divide(taxable, 6, RoundingMode.HALF_UP);

        return new TaxResult(
                rules.year(), in.method(),
                r(revenue), r(expenses), r(taxable), r(base),
                r(vsd), r(psd), r(pension),
                r(gpmBase), r(gpm), r(total),
                r(total.divide(MONTHS, 10, RoundingMode.HALF_UP)),
                effective.setScale(4, RoundingMode.HALF_UP),
                ceilingHit);
    }

    /**
     * IV GPM: iki 42 500 € – efektyvus tarifas pagal mokesčio kredito schemą (5 % → 15 % → 20 %);
     * viršijanti dalis apmokestinama progresiniais 20/25/32 % tarifais, sumuojant su kitomis pajamomis.
     * TIKRINTI: tikslios kredito formulės ir sumavimo tvarka (SPEC.md „Patikrinti prieš paleidimą“).
     */
    BigDecimal ivGpm(BigDecimal g, BigDecimal other) {
        return combinedGpm(other.add(g)).subtract(combinedGpm(other));
    }

    /** Mokestis nuo bendros sumos: kredito schema iki 42 500 €, toliau progresiniai tarifai. */
    private BigDecimal combinedGpm(BigDecimal g) {
        BigDecimal t1 = rules.creditStart();
        BigDecimal t2 = rules.creditMid();
        BigDecimal t3 = rules.creditEnd();
        if (g.compareTo(t1) <= 0) {
            return g.multiply(rules.lowRate());
        }
        if (g.compareTo(t2) <= 0) {
            return g.multiply(interpolate(g, t1, t2, rules.lowRate(), rules.midRate()));
        }
        if (g.compareTo(t3) <= 0) {
            return g.multiply(interpolate(g, t2, t3, rules.midRate(), rules.highRate()));
        }
        BigDecimal upToCap = t3.multiply(rules.highRate());
        return upToCap.add(progressive(g).subtract(progressive(t3)));
    }

    private static BigDecimal interpolate(BigDecimal x, BigDecimal x0, BigDecimal x1, BigDecimal y0, BigDecimal y1) {
        BigDecimal frac = x.subtract(x0).divide(x1.subtract(x0), 12, RoundingMode.HALF_UP);
        return y0.add(y1.subtract(y0).multiply(frac));
    }

    /** Progresinis GPM nuo bendros sumos: 20 % iki 36 VDU, 25 % iki 60 VDU, 32 % virš. */
    BigDecimal progressive(BigDecimal x) {
        BigDecimal b1 = rules.bracket1Vdu().multiply(rules.vdu());
        BigDecimal b2 = rules.bracket2Vdu().multiply(rules.vdu());
        BigDecimal tax = x.min(b1).multiply(rules.rate1());
        if (x.compareTo(b1) > 0) {
            tax = tax.add(x.min(b2).subtract(b1).multiply(rules.rate2()));
        }
        if (x.compareTo(b2) > 0) {
            tax = tax.add(x.subtract(b2).multiply(rules.rate3()));
        }
        return tax;
    }

    private static BigDecimal r(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
