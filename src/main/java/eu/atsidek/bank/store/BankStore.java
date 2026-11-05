package eu.atsidek.bank.store;

import eu.atsidek.bank.Category;
import eu.atsidek.bank.classify.Classifier;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Visi SQL vienoje vietoje; kiekviena užklausa filtruojama pagal user_id. */
@Repository
public class BankStore {

    public record Account(long id, String iban, String ibanHash, String label, String bankName) {}

    public record ImportRow(String id, String accountLabel, String format, LocalDate from, LocalDate to,
                            int txCount, LocalDateTime createdAt) {}

    public record Tx(long id, String importId, LocalDate date, BigDecimal amount, String direction, String name,
                     String ibanHash, String remittance, Category category, double confidence, String reason,
                     boolean confirmed) {}

    public record RuleRow(long id, String matchType, String matchValue, String displayName, Category category) {}

    public record MonthSum(int month, Category category, BigDecimal sum) {}

    private final JdbcTemplate jdbc;

    public BankStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- sąskaitos

    public List<Account> accounts(String user) {
        return jdbc.query("SELECT id, iban, iban_hash, label, bank_name FROM bank_account WHERE user_id = ? ORDER BY id",
                (rs, i) -> new Account(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), user);
    }

    public Optional<Account> accountByIban(String user, String iban) {
        return accounts(user).stream().filter(a -> iban.equals(a.iban())).findFirst();
    }

    public long createAccount(String user, String iban, String ibanHash, String label, String bank) {
        jdbc.update("INSERT INTO bank_account (user_id, iban, iban_hash, label, bank_name) VALUES (?,?,?,?,?)",
                user, iban, ibanHash, label, bank);
        return jdbc.queryForObject("SELECT MAX(id) FROM bank_account WHERE user_id = ?", Long.class, user);
    }

    // ---- importai

    public void insertImport(String id, String user, long accountId, String format, LocalDate from, LocalDate to, int count) {
        jdbc.update("INSERT INTO statement_import (id, user_id, account_id, source_format, period_from, period_to, tx_count, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?)", id, user, accountId, format,
                from == null ? null : Date.valueOf(from), to == null ? null : Date.valueOf(to), count,
                Timestamp.valueOf(LocalDateTime.now()));
    }

    public List<ImportRow> imports(String user) {
        return jdbc.query("SELECT i.id, a.label, i.source_format, i.period_from, i.period_to, i.tx_count, i.created_at"
                        + " FROM statement_import i JOIN bank_account a ON a.id = i.account_id"
                        + " WHERE i.user_id = ? ORDER BY i.created_at DESC",
                (rs, n) -> new ImportRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class), rs.getInt(6),
                        rs.getTimestamp(7).toLocalDateTime()), user);
    }

    public boolean importExists(String user, String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM statement_import WHERE id = ? AND user_id = ?", Integer.class, id, user) > 0;
    }

    public void deleteImport(String user, String id) {
        jdbc.update("DELETE FROM bank_transaction WHERE import_id = ? AND user_id = ?", id, user);
        jdbc.update("DELETE FROM statement_import WHERE id = ? AND user_id = ?", id, user);
    }

    public void deleteAll(String user) {
        jdbc.update("DELETE FROM bank_transaction WHERE user_id = ?", user);
        jdbc.update("DELETE FROM statement_import WHERE user_id = ?", user);
        jdbc.update("DELETE FROM classification_rule WHERE user_id = ?", user);
        jdbc.update("DELETE FROM bank_account WHERE user_id = ?", user);
    }

    // ---- operacijos

    public Set<String> existingDedupKeys(String user, Collection<String> keys) {
        Set<String> found = new HashSet<>();
        List<String> list = new ArrayList<>(keys);
        for (int i = 0; i < list.size(); i += 500) {
            List<String> part = list.subList(i, Math.min(list.size(), i + 500));
            String in = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(user);
            args.addAll(part);
            found.addAll(jdbc.queryForList("SELECT dedup_key FROM bank_transaction WHERE user_id = ? AND dedup_key IN (" + in + ")",
                    String.class, args.toArray()));
        }
        return found;
    }

    public void insertTx(String user, String importId, long accountId, LocalDate date, BigDecimal amount, String direction,
                         String name, String ibanHash, String remittance, String dedupKey, Classifier.Result c) {
        jdbc.update("INSERT INTO bank_transaction (user_id, import_id, account_id, booking_date, amount, direction,"
                        + " counterparty_name, counterparty_iban_hash, remittance, dedup_key, category, confidence, reason, confirmed, rule_id)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,FALSE,?)",
                user, importId, accountId, Date.valueOf(date), amount, direction, trunc(name, 300), ibanHash,
                trunc(remittance, 1000), dedupKey, c == null ? null : c.category().name(),
                c == null ? null : BigDecimal.valueOf(c.confidence()), c == null ? null : c.reason(),
                c == null ? null : c.ruleId());
    }

    private static String trunc(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static final String TX_COLS = "id, import_id, booking_date, amount, direction, counterparty_name,"
            + " counterparty_iban_hash, remittance, category, confidence, reason, confirmed";

    private static Tx mapTx(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        String cat = rs.getString(9);
        return new Tx(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate.class), rs.getBigDecimal(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), cat == null ? Category.UNKNOWN : Category.valueOf(cat),
                rs.getDouble(10), rs.getString(11), rs.getBoolean(12));
    }

    public List<Tx> incomingOfImport(String user, String importId) {
        return jdbc.query("SELECT " + TX_COLS + " FROM bank_transaction WHERE user_id = ? AND import_id = ? AND direction = 'IN'"
                + " ORDER BY booking_date, id", BankStore::mapTx, user, importId);
    }

    public List<Tx> unconfirmedIncoming(String user) {
        return jdbc.query("SELECT " + TX_COLS + " FROM bank_transaction WHERE user_id = ? AND direction = 'IN' AND confirmed = FALSE",
                BankStore::mapTx, user);
    }

    public Optional<Tx> tx(String user, long id) {
        return jdbc.query("SELECT " + TX_COLS + " FROM bank_transaction WHERE user_id = ? AND id = ?", BankStore::mapTx, user, id)
                .stream().findFirst();
    }

    public void confirm(String user, Collection<Long> ids, Category category) {
        for (long id : ids) {
            jdbc.update("UPDATE bank_transaction SET category = ?, confirmed = TRUE, confidence = 1.0,"
                    + " reason = 'Patvirtinta jūsų' WHERE user_id = ? AND id = ? AND direction = 'IN'", category.name(), user, id);
        }
    }

    public void applyRule(String user, long txId, long ruleId, Category category) {
        jdbc.update("UPDATE bank_transaction SET category = ?, confidence = 1.0, reason = ?, rule_id = ?"
                        + " WHERE user_id = ? AND id = ? AND confirmed = FALSE", category.name(),
                "Pagal jūsų taisyklę: " + category.label(), ruleId, user, txId);
    }

    /** Praėjusiuose importuose patvirtinti mokėtojai (IBAN maiša ir vardai) – klasifikavimo istorijai. */
    public List<String[]> confirmedIncomePayers(String user) {
        return jdbc.query("SELECT DISTINCT counterparty_iban_hash, counterparty_name FROM bank_transaction"
                        + " WHERE user_id = ? AND confirmed = TRUE AND category = 'BUSINESS_INCOME'",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, user);
    }

    // ---- taisyklės

    public List<RuleRow> rules(String user) {
        return jdbc.query("SELECT id, match_type, match_value, display_name, category FROM classification_rule"
                        + " WHERE user_id = ? ORDER BY id",
                (rs, n) -> new RuleRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        Category.valueOf(rs.getString(5))), user);
    }

    public long saveRule(String user, String type, String value, String displayName, Category category) {
        List<Long> existing = jdbc.queryForList("SELECT id FROM classification_rule WHERE user_id = ? AND match_type = ?"
                + " AND match_value = ?", Long.class, user, type, value);
        if (!existing.isEmpty()) {
            jdbc.update("UPDATE classification_rule SET category = ? WHERE id = ?", category.name(), existing.get(0));
            return existing.get(0);
        }
        jdbc.update("INSERT INTO classification_rule (user_id, match_type, match_value, display_name, category, created_at)"
                + " VALUES (?,?,?,?,?,?)", user, type, value, displayName, category.name(), Timestamp.valueOf(LocalDateTime.now()));
        return jdbc.queryForObject("SELECT MAX(id) FROM classification_rule WHERE user_id = ?", Long.class, user);
    }

    public void deleteRule(String user, long id) {
        jdbc.update("DELETE FROM classification_rule WHERE id = ? AND user_id = ?", id, user);
    }

    // ---- suvestinė

    public List<MonthSum> confirmedMonthlySums(String user, int year) {
        return jdbc.query("SELECT MONTH(booking_date), category, SUM(amount) FROM bank_transaction"
                        + " WHERE user_id = ? AND confirmed = TRUE AND direction = 'IN' AND YEAR(booking_date) = ?"
                        + " AND category IN ('BUSINESS_INCOME','SALARY') GROUP BY MONTH(booking_date), category",
                (rs, n) -> new MonthSum(rs.getInt(1), Category.valueOf(rs.getString(2)), rs.getBigDecimal(3)), user, year);
    }
}
