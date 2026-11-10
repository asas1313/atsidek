package eu.atsidek.goals;

/**
 * Viena atsakymo reikšmė su jos kilme, kaip reikalauja spec.md 4 skyrius. {@code source}/{@code asOf}
 * turi reikšmę tik statistinėms reikšmėms (F2 fazė); {@code ASSUMPTION} kilmei {@code source} yra vieno
 * sakinio paaiškinimas, kodėl tai prielaida, o ne duomenų šaltinis, todėl {@code asOf} lieka {@code null}.
 */
public record ResolvedValue(Object value, Origin origin, String source, String asOf) {
}
