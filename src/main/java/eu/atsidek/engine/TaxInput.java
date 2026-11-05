package eu.atsidek.engine;

import java.math.BigDecimal;

/**
 * @param ivRevenue           numatomos metinės IV pajamos
 * @param method              išlaidų metodas
 * @param actualExpenses      faktinės išlaidos (naudojamos, kai method = ACTUAL, ir palyginimui)
 * @param otherIncome         kitos apmokestinamosios pajamos (darbo užmokestis, nuoma)
 * @param pensionAccumulation ar dalyvauja II pakopos pensijų kaupime (+3 %)
 * @param psdMinMonths        kiek mėnesių (0–12) taikoma minimali PSD įmoka: mėnesiai, kai IV veiklą vykdai ir PSD
 *                            už tave nemoka darbdavys ar kitas asmuo
 */
public record TaxInput(
        BigDecimal ivRevenue,
        ExpenseMethod method,
        BigDecimal actualExpenses,
        BigDecimal otherIncome,
        boolean pensionAccumulation,
        int psdMinMonths) {

    /** Minimalus PSD visus 12 mėnesių (numatytoji reikšmė). */
    public TaxInput(BigDecimal ivRevenue, ExpenseMethod method, BigDecimal actualExpenses, BigDecimal otherIncome,
                    boolean pensionAccumulation) {
        this(ivRevenue, method, actualExpenses, otherIncome, pensionAccumulation, 12);
    }

    public TaxInput {
        psdMinMonths = Math.max(0, Math.min(12, psdMinMonths));
        ivRevenue = nonNegative(ivRevenue);
        actualExpenses = nonNegative(actualExpenses);
        otherIncome = nonNegative(otherIncome);
        if (method == null) {
            method = ExpenseMethod.FLAT_30;
        }
    }

    public TaxInput withMethod(ExpenseMethod m) {
        return new TaxInput(ivRevenue, m, actualExpenses, otherIncome, pensionAccumulation, psdMinMonths);
    }

    private static BigDecimal nonNegative(BigDecimal v) {
        return v == null || v.signum() < 0 ? BigDecimal.ZERO : v;
    }
}
