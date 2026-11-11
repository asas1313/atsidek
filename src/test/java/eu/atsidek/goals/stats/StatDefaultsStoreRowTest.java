package eu.atsidek.goals.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit testai (be DB) {@link StatDefaultsStore.Row#hasTraceableOrigin()} invariantui: PLAN-taupymas-agentas.md
 * A2 / A0 sprendimas #5 - kiekviena {@code stat_defaults} eilutė turi turėti netuščią {@code source} IR
 * ({@code as_of} datą ARBA {@code is_illustrative = true}).
 */
class StatDefaultsStoreRowTest {

    private static StatDefaultsStore.Row row(String source, LocalDate asOf, boolean illustrative) {
        return new StatDefaultsStore.Row("income", "members=1;workers=1", new BigDecimal("1000"), "EUR_PER_MONTH",
                source, "https://example.org", asOf, illustrative);
    }

    @Test
    void traceableWhenSourceAndAsOfDatePresentEvenIfNotIllustrative() {
        assertThat(row("VDA 2026 ataskaita", LocalDate.of(2026, 1, 1), false).hasTraceableOrigin()).isTrue();
    }

    @Test
    void traceableWhenSourcePresentAndMarkedIllustrativeEvenWithoutAsOf() {
        assertThat(row("Įvertinta proporcingai nuo maketo reikšmės", null, true).hasTraceableOrigin()).isTrue();
    }

    @Test
    void notTraceableWhenAsOfMissingAndNotIllustrative() {
        // Šaltinis yra, bet jis nei turi tikrinamos datos, nei pažymėtas kaip iliustracinis - negalima
        // pristatyti kaip "solidi" statistika (PLAN A2: "trūkstamas papildyti arba žymėti is_illustrative").
        assertThat(row("VDA 2026 ataskaita", null, false).hasTraceableOrigin()).isFalse();
    }

    @Test
    void notTraceableWhenSourceBlank() {
        assertThat(row("   ", LocalDate.of(2026, 1, 1), false).hasTraceableOrigin()).isFalse();
    }

    @Test
    void notTraceableWhenSourceNull() {
        assertThat(row(null, LocalDate.of(2026, 1, 1), false).hasTraceableOrigin()).isFalse();
    }
}
