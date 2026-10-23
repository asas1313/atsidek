package lt.atsidek.engine;

import java.math.BigDecimal;

/**
 * @param gpm         GPM nuo IV pajamų
 * @param extraGpm    papildomas GPM nuo kitų pajamų, atsirandantis dėl sumavimo (darbdavys išskaičiuoja 20 %,
 *                    o dalis pajamų patenka į 25/32 % ribas); jį reikia sumokėti deklaruojant
 */
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
        BigDecimal extraGpm,
        BigDecimal total,
        BigDecimal monthlyReserve,
        BigDecimal effectiveRate,
        boolean sodraCeilingHit) {

    public BigDecimal sodraTotal() {
        return vsd.add(psd).add(pensionAccumulation);
    }
}
