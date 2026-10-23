package lt.atsidek.engine;

import java.math.BigDecimal;

/**
 * Vienų metų mokesčių taisyklių parametrai. Kiekvienais metais pridedama nauja versija
 * (pvz. {@link #RULES_2026}), todėl senus rezultatus visada galima paaiškinti.
 */
public record TaxRules(
        int year,
        BigDecimal vdu,
        BigDecimal vsdRate,
        BigDecimal psdRate,
        BigDecimal pensionAccumulationRate,
        BigDecimal sodraBaseShare,
        BigDecimal minMonthlyPsd,
        BigDecimal sodraCeilingVdu,
        BigDecimal expenseFlatShare,
        BigDecimal creditMax,
        BigDecimal creditPhaseOutStart,
        BigDecimal creditPhaseOutEnd,
        BigDecimal bracket1Vdu,
        BigDecimal bracket2Vdu,
        BigDecimal rate1,
        BigDecimal rate2,
        BigDecimal rate3) {

    /**
     * Parametrai sutikrinti su VMI/Sodros individualios veiklos skaičiuokle (2026-10-23, žr.
     * VERIFIKAVIMO-REZULTATAI.md). Pajamų mokesčio kreditas: iki 20 000 € – 0,15·P; 20 000–42 500 € –
     * 0,15·P − P·(P − 20 000)·0,15/22 500; virš 42 500 € – nėra. GPMĮ 18-2 str.
     */
    public static final TaxRules RULES_2026 = new TaxRules(
            2026,
            new BigDecimal("2312.15"),
            new BigDecimal("0.1252"),
            new BigDecimal("0.0698"),
            new BigDecimal("0.03"),
            new BigDecimal("0.9"),
            new BigDecimal("80.48"),
            new BigDecimal("43"),
            new BigDecimal("0.30"),
            new BigDecimal("0.15"),
            new BigDecimal("20000"),
            new BigDecimal("42500"),
            new BigDecimal("36"),
            new BigDecimal("60"),
            new BigDecimal("0.20"),
            new BigDecimal("0.25"),
            new BigDecimal("0.32"));
}
