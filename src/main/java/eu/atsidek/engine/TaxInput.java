package eu.atsidek.engine;

import java.math.BigDecimal;

/**
 * @param ivRevenue           numatomos metinės IV pajamos
 * @param method              išlaidų metodas
 * @param actualExpenses      faktinės išlaidos (naudojamos, kai method = ACTUAL, ir palyginimui)
 * @param otherIncome         kitos apmokestinamosios pajamos (darbo užmokestis, nuoma)
 * @param pensionAccumulation ar dalyvauja II pakopos pensijų kaupime (+3 %)
 */
public record TaxInput(
        BigDecimal ivRevenue,
        ExpenseMethod method,
        BigDecimal actualExpenses,
        BigDecimal otherIncome,
        boolean pensionAccumulation) {

    public TaxInput {
        ivRevenue = nonNegative(ivRevenue);
        actualExpenses = nonNegative(actualExpenses);
        otherIncome = nonNegative(otherIncome);
        if (method == null) {
            method = ExpenseMethod.FLAT_30;
        }
    }

    public TaxInput withMethod(ExpenseMethod m) {
        return new TaxInput(ivRevenue, m, actualExpenses, otherIncome, pensionAccumulation);
    }

    private static BigDecimal nonNegative(BigDecimal v) {
        return v == null || v.signum() < 0 ? BigDecimal.ZERO : v;
    }
}
