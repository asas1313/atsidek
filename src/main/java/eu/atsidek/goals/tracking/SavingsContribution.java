package eu.atsidek.goals.tracking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Vienas mėnesio įrašas apie faktiškai įneštą sumą ({@code savings_contribution} lentelė, V9
 * migracija). {@code month} - to mėnesio pirma diena (verslo data, ne momentas), DB
 * {@code UNIQUE(goal_id, month)} leidžia tik vieną eilutę per mėnesį (pakartotinis žymėjimas - upsert).
 */
public record SavingsContribution(long id, UUID goalId, LocalDate month, BigDecimal amount) {
}
