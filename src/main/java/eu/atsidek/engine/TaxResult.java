package eu.atsidek.engine;

import java.math.BigDecimal;

/**
 * @param gpm         GPM nuo IV pajamų
 * @param extraGpm        papildomas GPM nuo kitų pajamų, atsirandantis dėl sumavimo (darbdavys išskaičiuoja 20 %,
 *                        o dalis pajamų patenka į 25/32 % ribas); jį reikia sumokėti deklaruojant
 * @param netMonthlyIncome grynosios mėnesio pajamos po mokesčių atsidėjimo IR vartotojo nurodytų
 *                        faktinių IV išlaidų - {@code (revenue - actualExpenses - total) / 12}, net
 *                        kai mokesčiams skaičiuoti taikomas 30 % normos metodas (owner instrukcija,
 *                        2026-11-16, po B6 eval #11: ankstesnė formulė {@code (revenue - total) / 12}
 *                        faktinių išlaidų neatimdavo VISAI, net kai vartotojas jas nurodė - žr.
 *                        {@link TaxEngine#calculate}). Kai {@code actualExpenses} nenurodytos,
 *                        {@link TaxInput}'o kanoninis konstruktorius jas paverčia {@code ZERO}, tad
 *                        atimama 0. {@code revenue}/{@code total} yra metinės sumos, žr. design
 *                        decision #3 - skaičiuojama {@link TaxEngine}, NE Python pusėje, kad AI
 *                        agentas niekada pats neskaičiuotų pinigų)
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
