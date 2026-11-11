package eu.atsidek.goals.plan;

/**
 * Premium {@code /api/v1/savings/plan} atsakymo dalis. Tyčia tuščia šioje fazėje (A3) - tikras
 * turinys (keli tikslai, IV ryšys, scenarijai) ateina su atitinkamomis {@code Feature} funkcijomis
 * vėliau; {@code premium} RAKTAS vis tiek privalo būti serializuojamas kaip {@code {}}, ne praleistas,
 * kai teisės yra (PLAN-taupymas-agentas.md: "premium sekcija grąžinama tik turint teises; anonimui
 * jos nėra" - rakto BUVIMAS/NEBUVIMAS sprendžiamas {@link SavingsPlanService}, ne šiame tipe).
 */
public record Premium() {
}
