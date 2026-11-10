package eu.atsidek.goals.stats;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Taupymo tikslų statistikos numatymų ({@code stat_defaults} lentelė, žr. V3/V4 Flyway migracijas)
 * skaitymo repository. Visas SQL vienoje vietoje (kaip {@code eu.atsidek.bank.store.BankStore}).
 */
@Repository
public class StatDefaultsStore {

    /**
     * @param key            reikšmės tipas, pvz. {@code "income"}, {@code "expenses"}, {@code "typical_amount"}
     * @param segment        segmentas, pvz. {@code "members=4;workers=2"} arba {@code "goalType=CHILD"}
     * @param value          reikšmė
     * @param unit           vienetas, pvz. {@code "EUR_PER_MONTH"}, {@code "EUR"}
     * @param source         šaltinio aprašymas
     * @param sourceUrl      nuoroda į konkretų puslapį; {@code null}, kai nėra išorinio šaltinio
     * @param asOf           duomenų data; {@code null}, kai nėra realios duomenų datos (iliustracinė reikšmė)
     * @param illustrative   ar reikšmė iliustracinė (neparemta patikrinta statistika)
     */
    public record Row(String key, String segment, BigDecimal value, String unit, String source, String sourceUrl,
                      LocalDate asOf, boolean illustrative) {}

    private final JdbcTemplate jdbc;

    public StatDefaultsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find(String key, String segment) {
        return jdbc.query("SELECT key, segment, value, unit, source, source_url, as_of, is_illustrative"
                        + " FROM stat_defaults WHERE key = ? AND segment = ?",
                StatDefaultsStore::map, key, segment).stream().findFirst();
    }

    /** Visos eilutės duotam raktui, stabilia tvarka (pagal id), kad fallback pasirinkimas būtų deterministinis. */
    public List<Row> findAllByKey(String key) {
        return jdbc.query("SELECT key, segment, value, unit, source, source_url, as_of, is_illustrative"
                + " FROM stat_defaults WHERE key = ? ORDER BY id", StatDefaultsStore::map, key);
    }

    private static Row map(ResultSet rs, int n) throws SQLException {
        java.sql.Date asOf = rs.getDate(7);
        return new Row(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4), rs.getString(5),
                rs.getString(6), asOf == null ? null : asOf.toLocalDate(), rs.getBoolean(8));
    }
}
