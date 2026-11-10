package eu.atsidek.goals;

import java.math.BigDecimal;

/** Vienas kaupimo grafiko taškas: kalendorinių metų pabaiga, sukaupta suma ir to momento (infliacinis) tikslas. */
public record SeriesPoint(int year, BigDecimal balance, BigDecimal target) {
}
