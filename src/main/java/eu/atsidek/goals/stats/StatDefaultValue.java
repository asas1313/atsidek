package eu.atsidek.goals.stats;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Viena atsakymo reikšmė su jos kilme (spec.md 4 skyrius: "kiekviena reikšmė pateikiama su source,
 * sourceUrl, asOf"). {@code origin} čia – paprasta eilutė, ne {@code eu.atsidek.goals.Origin} enum (tas
 * paketas dar nesujungtas į main, žr. PR aprašą), bet reikšmės ({@code "STATISTICS"}/{@code "ASSUMPTION"})
 * sąmoningai sutampa su F1 PR #22 {@code Origin} enum vardais, kad susijungimas būtų trivialus.
 *
 * @param value         reikšmė
 * @param unit          vienetas, pvz. {@code "EUR_PER_MONTH"}, {@code "EUR"}, {@code "PERCENT"}
 * @param origin        {@code "STATISTICS"} arba {@code "ASSUMPTION"}
 * @param source        šaltinio aprašymas (ASSUMPTION atveju – vieno sakinio paaiškinimas, ne duomenų šaltinis)
 * @param sourceUrl     nuoroda į konkretų puslapį; {@code null}, kai nėra išorinio šaltinio (ASSUMPTION arba
 *                      grynai iliustracinė vidinė reikšmė be atitikmens realiame šaltinyje)
 * @param asOf          duomenų data; {@code null}, kai nėra realios duomenų datos
 * @param illustrative  ar reikšmė iliustracinė (neparemta patikrinta statistika)
 */
public record StatDefaultValue(
        BigDecimal value,
        String unit,
        String origin,
        String source,
        String sourceUrl,
        LocalDate asOf,
        boolean illustrative) {
}
