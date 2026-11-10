package eu.atsidek.goals;

/**
 * Kilmė, iš kurios atkeliavo viena atsakymo reikšmė. Prioritetas naudojimui (UI ženkliukas rodo faktinę
 * kilmę, ne šią eilę): {@code USER} > {@code AI} > {@code STATISTICS} > {@code ASSUMPTION}.
 * {@code ASSUMPTION} (PLAN.md K5) – ilgalaikė modelio prielaida (pvz. grąža, infliacija), ne trumpalaikė
 * statistinė prognozė, todėl atskirta nuo {@code STATISTICS}.
 */
public enum Origin {
    USER, AI, STATISTICS, ASSUMPTION
}
