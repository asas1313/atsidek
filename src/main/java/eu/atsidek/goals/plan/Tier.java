package eu.atsidek.goals.plan;

/**
 * Taupymo plano API lygis (PLAN-taupymas-agentas.md A3/A4). Sąmoningai NAUJAS, siauras enum - tik
 * šios API atsakymo laukui {@code tier}, ne bendra vartotojo prenumeratos abstrakcija (ta lemiama per
 * {@code eu.atsidek.entitlement.Feature}/{@code EntitlementService} atskirai, pagal kiekvieną
 * {@code locked_features} elementą, žr. {@link SavingsPlanService}).
 */
public enum Tier {
    FREE, PREMIUM
}
