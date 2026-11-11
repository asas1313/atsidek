package eu.atsidek.entitlement;

/**
 * Uždara premium funkcijų aibė (žr. docs/adr/0002-entitlements-data-model-and-beta-flag.md).
 * Nauja premium funkcija vėliau = viena nauja enum reikšmė, ne schemos keitimas.
 */
public enum Feature {
    SAVINGS_TRACKING,
    SAVINGS_MULTI_GOAL,
    SAVINGS_SCENARIOS,
    SAVINGS_IV_LINK,
    AI_PLAN_CHAT,
    PDF_EXPORT
}
