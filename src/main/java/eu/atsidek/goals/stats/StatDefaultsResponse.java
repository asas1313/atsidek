package eu.atsidek.goals.stats;

/**
 * {@code GET /goals/stats/defaults} / {@code GET /api/v1/goals/stats/defaults} atsakymas (spec.md 4 skyrius).
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
