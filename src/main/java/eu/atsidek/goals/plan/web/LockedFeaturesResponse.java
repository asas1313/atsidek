package eu.atsidek.goals.plan.web;

import eu.atsidek.entitlement.Feature;
import java.util.List;

/**
 * {@code GET /goals/savings/locked-features} atsakymas (A5, PLAN-taupymas-agentas.md). Tyčia camelCase
 * (ne snake_case kaip {@code /api/v1/savings/plan}) - šis endpoint'as skirtas TIK
 * {@code taupymo-tikslai.html} naršyklės JS, kuri visus kitus šios formos atsakymus (pvz.
 * {@code GoalResult}, {@code StatDefaultsResponse}) skaito camelCase laukais; snake_case sutartis
 * taikoma tik viešam/agento API sluoksniui po {@code /api/v1}.
 *
 * @param lockedFeatures {@code Feature} reikšmės, kurių dabartinis vartotojas (anoniminis arba
 *                       prisijungęs) neturi - tuščias, kai turi visas (žr. {@code SavingsPlanService})
 */
public record LockedFeaturesResponse(List<Feature> lockedFeatures) {
}
