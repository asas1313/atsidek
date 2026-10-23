package lt.atsidek.engine;

import java.math.BigDecimal;

public record TaxResult(
        int ruleYear,
        ExpenseMethod method,
        BigDecimal revenue,
        BigDecimal expenses,
        BigDecimal taxableIncome,
        BigDecimal sodraBase,
        BigDecimal vsd,
        BigDecimal psd,
        BigDecimal pensionAccumulation,
        BigDecimal gpmBase,
        BigDecimal gpm,
        BigDecimal total,
        BigDecimal monthlyReserve,
        BigDecimal effectiveRate,
        boolean sodraCeilingHit) {

    public BigDecimal sodraTotal() {
        return vsd.add(psd).add(pensionAccumulation);
    }
}
