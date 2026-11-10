package eu.atsidek.goals;

import java.math.BigDecimal;

/**
 * @param annualReturn    metinė investicijų grąža procentais; {@code null} – naudojama numatytoji (ASSUMPTION)
 * @param annualInflation metinė infliacija procentais; {@code null} – naudojama numatytoji (ASSUMPTION)
 */
public record Assumptions(BigDecimal annualReturn, BigDecimal annualInflation) {
}
