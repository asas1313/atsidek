package eu.atsidek.goals.tracking;

import eu.atsidek.goals.GoalInput;
import eu.atsidek.goals.GoalType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Vieno vartotojo vienintelis taupymo tikslas ({@code savings_goal} lentelė, V8 migracija).
 * {@code input} - visas originalus {@link GoalInput}, serializuotas į {@code assumptions} JSONB
 * stulpelį: progreso skaičiavimas (žr. {@link SavingsGoalService#progress}) jį perskaičiuoja iš
 * naujo su tuo pačiu {@code today} = {@code createdAt}, todėl planas (mėnesinė įmoka, terminas)
 * visada stabilus, nepriklausomai nuo to, kada progresas žiūrimas (PLAN.md §5/§6 task 9).
 *
 * @param id         stabilus, URL adresuojamas identifikatorius (reikalingas IDOR testui)
 * @param userId     savininko {@code app_user.id}
 * @param goalType   tikslo tipas (dubliuotas iš {@code input.goalType()} patogiam SQL filtravimui)
 * @param amount     tikslo suma šiandienos kainomis (dubliuota iš {@code input.amount()})
 * @param targetDate skaičiavimo metu nustatyta tikslo data; {@code null}, kai tikslas nepasiekiamas (WHEN)
 * @param input      pilnas originalus skaičiavimo užklausimas
 * @param createdAt  kada tikslas (šiuo pavidalu) buvo išsaugotas - TIMESTAMPTZ, UTC
 */
public record SavingsGoal(
        UUID id,
        UUID userId,
        GoalType goalType,
        BigDecimal amount,
        LocalDate targetDate,
        GoalInput input,
        Instant createdAt) {
}
