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
        BigDecimal creditStart,
        BigDecimal creditMid,
        BigDecimal creditEnd,
        BigDecimal lowRate,
        BigDecimal midRate,
        BigDecimal highRate,
        BigDecimal bracket1Vdu,
        BigDecimal bracket2Vdu,
        BigDecimal rate1,
        BigDecimal rate2,
        BigDecimal rate3,
        boolean contributionsDeductible) {

    /** Parametrai iš SPEC.md. Punktai, pažymėti „TIKRINTI“, dar nesutikrinti su GPM įstatymu ir VMI skaičiuokle. */
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
            new BigDecimal("20000"),   // iki čia 5 %
            new BigDecimal("35000"),   // TIKRINTI: 20 000–35 000 € efektyvus tarifas auga 5 → 15 %
            new BigDecimal("42500"),   // TIKRINTI: 35 000–42 500 € efektyvus tarifas auga 15 → 20 %
            new BigDecimal("0.05"),
            new BigDecimal("0.15"),
            new BigDecimal("0.20"),
            new BigDecimal("36"),
            new BigDecimal("60"),
            new BigDecimal("0.20"),
            new BigDecimal("0.25"),
            new BigDecimal("0.32"),
            true);                      // TIKRINTI: ar 2026 m. VSD/PSD dar atimamos iš GPM bazės
}
