package eu.atsidek.goals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * @param monthly      mėnesio įmoka: apskaičiuota (YEARS/DATE/CHILD_AGE) arba vartotojo nurodyta (WHEN)
 * @param reachable     ar tikslas pasiekiamas (visada {@code true}, išskyrus WHEN, kai nepasiekiama per 50 m.)
 * @param reachDate     data, kada tikslas pasiekiamas; pildoma tik WHEN režimui (kitiems terminas – įvestis, ne išvestis)
 * @param months        terminas mėnesiais (n arba rastas k); {@code null}, kai WHEN nepasiekiama
 * @param futureTarget  tikslo suma su infliacija termino pabaigoje; {@code null}, kai WHEN nepasiekiama
 * @param paid          iš viso įnešta (monthly × months); {@code null}, kai WHEN nepasiekiama
 * @param gain          investicinė grąža (sukaupta − įnešta, ne mažiau nei 0); {@code null}, kai WHEN nepasiekiama
 * @param freeCash      laisvi pinigai (pajamos − išlaidos); {@code null}, kai {@code income}
 *                      ({@link Household#income()}) nenurodytas (nežinomas, NE 0) - owner
 *                      sprendimas, 2026-11-17, po pirmo tikro staging patikrinimo
 * @param share         mėnesio įmokos dalis nuo laisvų pinigų; {@code null} tiksliai tada, kai {@code freeCash} yra {@code null}
 * @param verdict       vertinimas pagal {@code freeCash}/{@code share}; {@code null} tiksliai tada, kai {@code freeCash} yra {@code null}
 * @param series        kaupimo grafiko taškai (iki 24)
 * @param resolvedInputs kiekvienos reikšmės kilmė (žr. {@link ResolvedValue})
 */
public record GoalResult(
        BigDecimal monthly,
        boolean reachable,
        LocalDate reachDate,
        Integer months,
        BigDecimal futureTarget,
        BigDecimal paid,
        BigDecimal gain,
        BigDecimal freeCash,
        BigDecimal share,
        Verdict verdict,
        List<SeriesPoint> series,
        Map<String, ResolvedValue> resolvedInputs) {
}
