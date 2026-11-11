package eu.atsidek.entitlement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code app_user} lentelės (V5 Flyway migracija) skaitymo/rašymo repository. Raktas prisijungimui
 * yra {@code (auth_provider, provider_subject)} - ne email (žr. ADR-0001).
 */
@Repository
public class AppUserStore {

    private final JdbcTemplate jdbc;

    public AppUserStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByProviderSubject(String authProvider, String providerSubject) {
        return jdbc.query(
                "SELECT id, auth_provider, provider_subject, email, created_at FROM app_user"
                        + " WHERE auth_provider = ? AND provider_subject = ?",
                AppUserStore::map, authProvider, providerSubject).stream().findFirst();
    }

    /** Naudojama {@code MissingAppUserFilter} (task 15) ir {@code AccountController} (task 13). */
    public Optional<AppUser> findById(UUID id) {
        return jdbc.query("SELECT id, auth_provider, provider_subject, email, created_at FROM app_user WHERE id = ?",
                AppUserStore::map, id).stream().findFirst();
    }

    /**
     * Ištrina {@code app_user} eilutę - {@code entitlement}/{@code savings_goal} (ir per juos
     * {@code savings_contribution}) ištrinami automatiškai ({@code ON DELETE CASCADE}, V7/V8/V9
     * migracijos, ADR-0002). Naudojama {@code AccountController}'io (task 13).
     */
    public void deleteById(UUID id) {
        jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    }

    /**
     * Idempotentis upsert: jei {@code (authProvider, providerSubject)} eilutė jau egzistuoja, atnaujinamas
     * tik {@code email}, {@code id} nekeičiamas (perduotas {@code id} tada ignoruojamas - naudojamas
     * tik jei eilutės dar nėra). Kviečiama tiesiai iš {@code AppOidcUserService.loadUser()}.
     */
    public AppUser upsert(UUID id, String authProvider, String providerSubject, String email) {
        return jdbc.query(
                "INSERT INTO app_user (id, auth_provider, provider_subject, email) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (auth_provider, provider_subject) DO UPDATE SET email = EXCLUDED.email"
                        + " RETURNING id, auth_provider, provider_subject, email, created_at",
                AppUserStore::map, id, authProvider, providerSubject, email).get(0);
    }

    private static AppUser map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new AppUser(
                (UUID) rs.getObject("id"),
                rs.getString("auth_provider"),
                rs.getString("provider_subject"),
                rs.getString("email"),
                createdAt == null ? null : createdAt.toInstant());
    }
}
