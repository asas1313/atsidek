package eu.atsidek.goals.tracking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.atsidek.goals.GoalInput;
import eu.atsidek.goals.GoalType;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code savings_goal}/{@code savings_contribution} lentelių (V8/V9 migracijos) skaitymo/rašymo
 * repository. Kiekviena užklausa, kuri grąžina ar modifikuoja konkretaus vartotojo tikslą, yra
 * apribota {@code WHERE user_id = ?} pačiame SQL - savininkystės patikra niekada neatliekama
 * Java pusėje po to, kai eilutė jau gauta vien pagal id (PLAN.md §5/§7, docs/specs/saugumas/PLAN.md P0).
 */
@Repository
public class SavingsGoalStore {

    private static final String SELECT_GOAL =
            "SELECT id, user_id, goal_type, amount, target_date, assumptions, created_at FROM savings_goal";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SavingsGoalStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<SavingsGoal> findByUserId(UUID userId) {
        return jdbc.query(SELECT_GOAL + " WHERE user_id = ?", this::mapGoal, userId).stream().findFirst();
    }

    public Optional<SavingsGoal> findByIdAndUserId(UUID id, UUID userId) {
        return jdbc.query(SELECT_GOAL + " WHERE id = ? AND user_id = ?", this::mapGoal, id, userId).stream()
                .findFirst();
    }

    /**
     * "Lygiai vienas tikslas" taisyklė įgyvendinta DB {@code UNIQUE(user_id)} + upsert vietoje -
     * pakeitimas palieka tą pačią eilutę (tą pačią {@code id}), todėl tikslas turi stabilų,
     * per URL adresuojamą id (reikalingas IDOR testui) net po pakeitimo.
     */
    public SavingsGoal upsert(UUID userId, GoalType goalType, BigDecimal amount, LocalDate targetDate, GoalInput input) {
        String json = writeJson(input);
        return jdbc.query(
                "INSERT INTO savings_goal (id, user_id, goal_type, amount, target_date, assumptions) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb) "
                        + "ON CONFLICT (user_id) DO UPDATE SET goal_type = EXCLUDED.goal_type, "
                        + "amount = EXCLUDED.amount, target_date = EXCLUDED.target_date, "
                        + "assumptions = EXCLUDED.assumptions "
                        + "RETURNING id, user_id, goal_type, amount, target_date, assumptions, created_at",
                this::mapGoal, UUID.randomUUID(), userId, goalType.name(), amount, targetDate, json).get(0);
    }

    public int countContributions(UUID goalId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM savings_contribution WHERE goal_id = ?", Integer.class, goalId);
        return count == null ? 0 : count;
    }

    public void deleteContributions(UUID goalId) {
        jdbc.update("DELETE FROM savings_contribution WHERE goal_id = ?", goalId);
    }

    public List<SavingsContribution> findContributions(UUID goalId) {
        return jdbc.query(
                "SELECT id, goal_id, month, amount FROM savings_contribution WHERE goal_id = ? ORDER BY month",
                SavingsGoalStore::mapContribution, goalId);
    }

    public SavingsContribution upsertContribution(UUID goalId, LocalDate month, BigDecimal amount) {
        return jdbc.query(
                "INSERT INTO savings_contribution (goal_id, month, amount) VALUES (?, ?, ?) "
                        + "ON CONFLICT (goal_id, month) DO UPDATE SET amount = EXCLUDED.amount "
                        + "RETURNING id, goal_id, month, amount",
                SavingsGoalStore::mapContribution, goalId, month, amount).get(0);
    }

    private SavingsGoal mapGoal(ResultSet rs, int rowNum) throws SQLException {
        Date targetDate = rs.getDate("target_date");
        GoalInput input = readJson(rs.getString("assumptions"));
        return new SavingsGoal(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("user_id"),
                GoalType.valueOf(rs.getString("goal_type")),
                rs.getBigDecimal("amount"),
                targetDate == null ? null : targetDate.toLocalDate(),
                input,
                rs.getTimestamp("created_at").toInstant());
    }

    private static SavingsContribution mapContribution(ResultSet rs, int rowNum) throws SQLException {
        return new SavingsContribution(
                rs.getLong("id"),
                (UUID) rs.getObject("goal_id"),
                rs.getDate("month").toLocalDate(),
                rs.getBigDecimal("amount"));
    }

    private String writeJson(GoalInput input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Nepavyko serializuoti GoalInput į assumptions JSONB", e);
        }
    }

    private GoalInput readJson(String json) {
        try {
            return objectMapper.readValue(json, GoalInput.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Nepavyko nuskaityti GoalInput iš assumptions JSONB", e);
        }
    }
}
