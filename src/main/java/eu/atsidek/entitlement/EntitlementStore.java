package eu.atsidek.entitlement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code entitlement} (V7) ir {@code app_setting} (V6) lentelių skaitymo repository. Vienintelis
 * skaitymo šaltinis, naudojamas {@link EntitlementService}'o patikros taškui.
 */
@Repository
public class EntitlementStore {

    private final JdbcTemplate jdbc;

    public EntitlementStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Entitlement> findByUserAndFeature(UUID userId, Feature feature) {
        return jdbc.query(
                "SELECT id, user_id, feature, source, valid_from, valid_until, created_at FROM entitlement"
                        + " WHERE user_id = ? AND feature = ?",
                EntitlementStore::map, userId, feature.name());
    }

    public Optional<String> findSettingValue(String settingKey) {
        return jdbc.query(
                "SELECT setting_value FROM app_setting WHERE setting_key = ?",
                rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(),
                settingKey);
    }

    /**
     * Įrašo naują {@code entitlement} eilutę - šiuo metu naudoja TIK {@code eu.atsidek.evalci}
     * (B6 gyvo eval'o CI testiniai vartotojai, žr. {@code EvalTokenService}); nė vienas tikro
     * srauto kelias (OAuth/mokėjimas) šiandien entitlement'ų neišduoda (žr.
     * {@code EntitlementService}'o klasės javadoc). Nėra unikalaus apribojimo
     * {@code (user_id, feature)} lentelėje - kartotinis kvietimas tai pačiai porai sukurtų antrą
     * eilutę (nekenksminga {@link EntitlementService#hasFeature} patikrai, kuri tik ieško BET
     * KOKIOS galiojančios eilutės, bet kviečiantis kodas (žr. {@code EvalTokenService}) pirma
     * patikrina {@link #findByUserAndFeature}, kad jos nedubliuotų).
     */
    public void grant(UUID userId, Feature feature, String source, Instant validFrom) {
        jdbc.update(
                "INSERT INTO entitlement (user_id, feature, source, valid_from) VALUES (?, ?, ?, ?)",
                userId, feature.name(), source, Timestamp.from(validFrom));
    }

    private static Entitlement map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp validUntil = rs.getTimestamp("valid_until");
        return new Entitlement(
                rs.getLong("id"),
                (UUID) rs.getObject("user_id"),
                Feature.valueOf(rs.getString("feature")),
                rs.getString("source"),
                rs.getTimestamp("valid_from").toInstant(),
                validUntil == null ? null : validUntil.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}
