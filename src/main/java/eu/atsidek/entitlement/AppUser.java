package eu.atsidek.entitlement;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code app_user} lentelės eilutė (žr. V5 Flyway migraciją, ADR-0001). {@code id} yra vidinis UUID,
 * generuojamas Java kode - ne el. paštas ir ne banko modulio {@code "google:" + sub} konvencija.
 * {@code (authProvider, providerSubject)} yra unikalus išorinis identitetas; el. paštas - tik duomenys,
 * niekada nėra raktas.
 *
 * @param id              vidinis UUID
 * @param authProvider    tapatybės teikėjas, pvz. {@code "google"}
 * @param providerSubject teikėjo OIDC {@code sub}
 * @param email           el. paštas (rodymui/kontaktui, ne raktas)
 * @param createdAt       pirmo prisijungimo laikas (UTC)
 */
public record AppUser(UUID id, String authProvider, String providerSubject, String email, Instant createdAt) {
}
