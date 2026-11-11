package eu.atsidek.entitlement;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code entitlement} lentelės eilutė (žr. V7 Flyway migraciją, ADR-0002). Vienas įrašas = vienas
 * suteikimas konkrečiam vartotojui ir funkcijai, galiojantis nuo {@code validFrom} iki (nebūtinai)
 * {@code validUntil}.
 *
 * @param id         eilutės ID
 * @param userId     {@code app_user.id}
 * @param feature    suteikta funkcija
 * @param source     suteikimo šaltinis, šiuo metu tik {@code "BETA"}; {@code "PAYMENT"} rezervuota
 *                   atskiros {@code monetizacija} inicijatyvos
 * @param validFrom  nuo kada galioja (UTC)
 * @param validUntil iki kada galioja (UTC); {@code null} = neterminuotas
 * @param createdAt  eilutės sukūrimo laikas (UTC)
 */
public record Entitlement(
        Long id, UUID userId, Feature feature, String source, Instant validFrom, Instant validUntil,
        Instant createdAt) {
}
