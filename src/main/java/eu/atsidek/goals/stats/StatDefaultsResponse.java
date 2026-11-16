package eu.atsidek.goals.stats;

/**
 * {@code GET /goals/stats/defaults} / {@code GET /api/v1/goals/stats/defaults} atsakymas (spec.md 4 skyrius).
 * <p>
 * {@code income}/{@code typicalAmount} naudojimas (owner instrukcija, 2026-11-16, stat_defaults
 * sprendimai #3-4 - patikrinta): {@code taupymo-tikslai.html}'o JS FORMA šias reikšmes naudoja
 * kaip žmogui matomą, redaguojamą pasiūlymą formos laukui prefill'inti (ne tylų skaičiavimą) -
 * žr. {@code applyStatsDefaults()} tame faile. B6 AI agento Python pusė (assistant/) šio endpoint'o
 * NIEKADA nekviečia - pajamos agento sraute visada arba iš ankstesnio {@code calculate_iv_taxes}
 * rezultato, arba vartotojo tiesiogiai nurodytos (niekada iš statistikos), o tikslo suma yra
 * PRIVALOMAS {@code calculate_savings_plan} įrankio laukas (niekada neužpildoma automatiškai) -
 * žr. {@code app/system_prompt.py}. Todėl šie du laukai PALIEKAMI (ne pašalinami) - jie realiai
 * naudojami, tik ne AI agento, o žmogaus pildomos formos kontekste.
 *
 * @param income          namų ūkio pajamos „į rankas“ / mėn., parinktos pagal members/workers segmentą
 * @param expenses        namų ūkio išlaidos / mėn., parinktos pagal members/workers segmentą
 * @param typicalAmount   tipinė tikslo suma pagal {@code goalType}; {@code null}, kai {@code goalType} nenurodytas
 * @param annualInflation metinė infliacijos prielaida (origin=ASSUMPTION, žr. {@link StatDefaultValue})
 * @param annualReturn    metinės investicijų grąžos prielaida (origin=ASSUMPTION)
 */
public record StatDefaultsResponse(
        StatDefaultValue income,
        StatDefaultValue expenses,
        StatDefaultValue typicalAmount,
        StatDefaultValue annualInflation,
        StatDefaultValue annualReturn) {
}
