package eu.atsidek.goals;

import java.math.BigDecimal;

/**
 * @param members  kiek žmonių namų ūkyje gyvena
 * @param workers  kiek iš jų dirba (≤ members)
 * @param income   namų ūkio mėnesio pajamos; {@code null} – nežinoma (F2 statistika šią fazę nepildo)
 * @param expenses namų ūkio mėnesio išlaidos; {@code null} – nežinoma
 * @param region   regionas (statistikos segmentavimui F2 fazėje)
 */
public record Household(Integer members, Integer workers, BigDecimal income, BigDecimal expenses, String region) {
}
