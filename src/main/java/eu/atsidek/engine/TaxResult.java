package eu.atsidek.engine;

import java.math.BigDecimal;

/**
 * @param gpm         GPM nuo IV pajamų
 * @param extraGpm        papildomas GPM nuo kitų pajamų, atsirandantis dėl sumavimo (darbdavys išskaičiuoja 20 %,
 *                        o dalis pajamų patenka į 25/32 % ribas); jį reikia sumokėti deklaruojant
 * @param netMonthlyIncome grynosios mėnesio pajamos po mokesčių atsidėjimo - {@code (revenue - total) / 12}
 *                        (PLAN-taupymas-agentas.B.plan.md B1, owner sprendimas 5; {@code revenue}/{@code total}
 *                        yra metinės sumos, žr. design decision #3 - skaičiuojama {@link TaxEngine}, NE Python
 *                        pusėje, kad AI agentas niekada patas neskaičiuotų pinigų)
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
        boolean sodraCeilingHit,
        BigDecimal netMonthlyIncome) {

    public BigDecimal sodraTotal() {
        return vsd.add(psd).add(pensionAccumulation);
    }
}
