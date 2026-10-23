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

        // GPM: taikant 30 % metodą Sodros įmokos iš GPM bazės neatimamos, taikant faktines išlaidas – atimamos
        BigDecimal deduction = in.method() == ExpenseMethod.ACTUAL ? vsd.add(psd).add(pension) : BigDecimal.ZERO;
        BigDecimal gpmBase = taxable.subtract(deduction).max(BigDecimal.ZERO);
        BigDecimal gpm = ivGpm(gpmBase);
        BigDecimal extra = extraGpmOnOther(gpmBase, in.otherIncome());

        BigDecimal total = gpm.add(extra).add(vsd).add(psd).add(pension);
        BigDecimal effective = taxable.signum() == 0
                ? BigDecimal.ZERO
                : total.divide(taxable, 6, RoundingMode.HALF_UP);

        return new TaxResult(
                rules.year(), in.method(),
                r(revenue), r(expenses), r(taxable), r(base),
                r(vsd), r(psd), r(pension),
                r(gpmBase), r(gpm), r(extra), r(total),
                r(total.divide(MONTHS, 10, RoundingMode.HALF_UP)),
                effective.setScale(4, RoundingMode.HALF_UP),
                ceilingHit);
    }

    /**
     * IV GPM: iki 42 500 € – 20 % minus pajamų mokesčio kreditas; virš – progresiniai tarifai (IV pajamos
     * į metinę sumą įskaičiuojamos pirmiausia, todėl kitų pajamų jos neveikia).
     */
    BigDecimal ivGpm(BigDecimal g) {
        if (g.compareTo(rules.creditPhaseOutEnd()) > 0) {
            return progressive(g);
        }
        BigDecimal start = rules.creditPhaseOutStart();
        BigDecimal creditRate = rules.creditMax();
        if (g.compareTo(start) > 0) {
            BigDecimal slope = rules.creditMax().divide(rules.creditPhaseOutEnd().subtract(start), 15, RoundingMode.HALF_UP);
            creditRate = creditRate.subtract(slope.multiply(g.subtract(start)));
        }
        return g.multiply(rules.rate1()).subtract(g.multiply(creditRate));
    }

    /** Skirtumas tarp progresinio GPM ant kitų pajamų (po IV) ir darbdavio išskaitomų 20 %. */
    BigDecimal extraGpmOnOther(BigDecimal g, BigDecimal other) {
        if (other.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return progressive(g.add(other)).subtract(progressive(g)).subtract(other.multiply(rules.rate1()))
                .max(BigDecimal.ZERO);
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
