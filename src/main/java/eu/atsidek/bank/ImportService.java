package eu.atsidek.bank;

import eu.atsidek.bank.classify.Classifier;
import eu.atsidek.bank.parse.ParseResult;
import eu.atsidek.bank.parse.ParserRegistry;
import eu.atsidek.bank.parse.RowError;
import eu.atsidek.bank.parse.StatementException;
import eu.atsidek.bank.store.BankStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Žingsniai FR-2…FR-5: atpažinimas, analizė, dublikatai, klasifikavimas. Viskas vienoje transakcijoje. */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    public static final int MAX_ACCOUNTS = 5;

    public record Report(String importId, String fileName, String format, String accountLabel, LocalDate from,
                         LocalDate to, int parsed, int incoming, int duplicatesSkipped, int needsReview,
                         List<RowError> errors) {}

    private final ParserRegistry parsers;
    private final BankStore store;
    private final Classifier classifier;
    private final CurrentUser currentUser;
    private final TransactionTemplate tx;
    private final String salt;

    public ImportService(ParserRegistry parsers, BankStore store, Classifier classifier, CurrentUser currentUser,
                         PlatformTransactionManager txm, @Value("${atsidek.bank.iban-salt}") String salt) {
        this.parsers = parsers;
        this.store = store;
        this.classifier = classifier;
        this.currentUser = currentUser;
        this.tx = new TransactionTemplate(txm);
        this.salt = salt;
    }

    public String ibanHash(String iban) {
        return iban == null ? null : Text.sha256(salt + "|" + Text.normalizeIban(iban));
    }

    public Report importFile(String fileName, byte[] data, Long accountId) throws IOException {
        if (data.length == 0) {
            throw new StatementException(StatementException.Kind.UNSUPPORTED_FORMAT, "Failas „" + fileName + "“ tuščias.");
        }
        ParseResult parsed = parsers.find(data, fileName).parse(new ByteArrayInputStream(data));
        if (parsed.transactions().isEmpty()) {
            throw new StatementException(StatementException.Kind.NO_TRANSACTIONS,
                    "Faile „" + fileName + "“ nerasta nė vienos tinkamos operacijos.");
        }
        String user = currentUser.id();
        Report report = tx.execute(status -> doImport(user, fileName, parsed, accountId));
        int total = parsed.errors().size() + parsed.transactions().size();
        if (parsed.errors().size() * 10 > total) {
            log.warn("Importas {} ({}): {} iš {} eilučių nepavyko", report.importId(), parsed.format(),
                    parsed.errors().size(), total);
        }
        log.info("Importas {} ({}): {} operacijų, {} dublikatų", report.importId(), parsed.format(),
                report.parsed(), report.duplicatesSkipped());
        return report;
    }

    private Report doImport(String user, String fileName, ParseResult parsed, Long chosenAccount) {
        Map<String, Long> accountIds = new HashMap<>();
        Map<String, String> labels = new HashMap<>();
        // sąskaitos
        for (NormalizedTransaction t : parsed.transactions()) {
            String key = t.accountIban() == null ? "-" : t.accountIban();
            if (accountIds.containsKey(key)) continue;
            BankStore.Account acc = resolveAccount(user, t.accountIban(), parsed.bankName(), chosenAccount);
            accountIds.put(key, acc.id());
            labels.put(key, acc.label());
        }
        long firstAccount = accountIds.values().iterator().next();

        // dublikatų raktai (to paties failo vienodi įrašai skiriami eilės numeriu)
        Map<String, Integer> seen = new HashMap<>();
        List<String> keys = new ArrayList<>();
        for (NormalizedTransaction t : parsed.transactions()) {
            String acct = t.accountIban() != null ? t.accountIban() : "acct:" + accountIds.get("-");
            String ref = t.bankRef() != null ? t.bankRef() : t.endToEndId() != null ? t.endToEndId() : Text.normalize(t.remittance());
            String base = String.join("|", acct, String.valueOf(t.bookingDate()), t.amount().toPlainString(),
                    t.direction().name(), ref);
            int n = seen.merge(base, 1, Integer::sum);
            keys.add(Text.sha256(n > 1 ? base + "#" + n : base));
        }
        Set<String> existing = store.existingDedupKeys(user, keys);

        Classifier.Context ctx = context(user);
        String importId = UUID.randomUUID().toString();
        // importo eilutė reikalinga prieš operacijas tik logiškai; FK nėra, tvarka nesvarbi
        int inserted = 0, incoming = 0, needsReview = 0, dup = 0;
        LocalDate from = null, to = null;
        for (int i = 0; i < parsed.transactions().size(); i++) {
            NormalizedTransaction t = parsed.transactions().get(i);
            if (existing.contains(keys.get(i))) {
                dup++;
                continue;
            }
            long acct = accountIds.get(t.accountIban() == null ? "-" : t.accountIban());
            String hash = ibanHash(t.counterpartyIban());
            Classifier.Result c = null;
            if (t.direction() == Direction.IN) {
                c = classifier.classify(new Classifier.Input(t.counterpartyName(), hash, t.remittance(), t.structuredRef()), ctx);
                incoming++;
                if (c.confidence() < 0.85) needsReview++;
            }
            store.insertTx(user, importId, acct, t.bookingDate(), t.amount(), t.direction().name(), t.counterpartyName(),
                    hash, t.remittance(), keys.get(i), c);
            inserted++;
            if (from == null || t.bookingDate().isBefore(from)) from = t.bookingDate();
            if (to == null || t.bookingDate().isAfter(to)) to = t.bookingDate();
        }
        String label = String.join(", ", new java.util.TreeSet<>(labels.values()));
        store.insertImport(importId, user, firstAccount, parsed.format(), from, to, inserted);
        return new Report(importId, fileName, parsed.format(), label, from, to, parsed.transactions().size(),
                incoming, dup, needsReview, parsed.errors());
    }

    BankStore.Account resolveAccount(String user, String iban, String bankName, Long chosen) {
        List<BankStore.Account> all = store.accounts(user);
        if (iban != null) {
            return all.stream().filter(a -> iban.equals(a.iban())).findFirst()
                    .orElseGet(() -> create(user, all.size(), iban, ibanHash(iban), formatIban(iban), null));
        }
        if (chosen != null) {
            return all.stream().filter(a -> a.id() == chosen).findFirst()
                    .orElseThrow(() -> new StatementException(StatementException.Kind.LIMIT, "Pasirinkta sąskaita nerasta."));
        }
        return all.stream().filter(a -> a.iban() == null && bankName != null && bankName.equals(a.bankName())).findFirst()
                .orElseGet(() -> create(user, all.size(), null, null, bankName == null ? "Sąskaita" : bankName, bankName));
    }

    private BankStore.Account create(String user, int count, String iban, String hash, String label, String bank) {
        if (count >= MAX_ACCOUNTS) {
            throw new StatementException(StatementException.Kind.LIMIT,
                    "Galima turėti iki " + MAX_ACCOUNTS + " sąskaitų. Ištrink nebereikalingas.");
        }
        long id = store.createAccount(user, iban, hash, label, bank);
        return new BankStore.Account(id, iban, hash, label, bank);
    }

    static String formatIban(String iban) {
        return iban.replaceAll("(.{4})", "$1 ").trim();
    }

    /** Klasifikatoriaus kontekstas: taisyklės, vartotojo sąskaitos, anksčiau patvirtinti mokėtojai. */
    public Classifier.Context context(String user) {
        List<Classifier.Rule> rules = store.rules(user).stream()
                .map(r -> new Classifier.Rule(r.id(), r.matchType(), r.matchValue(), r.category())).toList();
        Set<String> own = new HashSet<>();
        store.accounts(user).forEach(a -> { if (a.ibanHash() != null) own.add(a.ibanHash()); });
        Set<String> payers = new HashSet<>();
        for (String[] p : store.confirmedIncomePayers(user)) {
            String k = Classifier.payerKey(p[0], p[1]);
            if (k != null) payers.add(k);
            String byName = Classifier.payerKey(null, p[1]);
            if (byName != null) payers.add(byName);
        }
        return new Classifier.Context(rules, own, payers);
    }
}
