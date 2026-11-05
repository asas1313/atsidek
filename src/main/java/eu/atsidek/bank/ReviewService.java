package eu.atsidek.bank;

import eu.atsidek.bank.classify.Classifier;
import eu.atsidek.bank.store.BankStore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-6: pasiūlymai sugrupuoti pagal mokėtoją, patvirtinimas ir taisyklių kūrimas. */
@Service
public class ReviewService {

    public enum Bucket { DECISION, SUGGESTION, AUTO }

    public record Group(int index, String payer, int count, BigDecimal total, Category category, double confidence,
                        String reason, List<Long> ids, boolean confirmed, Bucket bucket) {
        public String idList() { return String.join(",", ids.stream().map(String::valueOf).toList()); }
        public boolean preChecked() { return confirmed || (bucket == Bucket.AUTO && category != Category.UNKNOWN); }
    }

    public record Decision(List<Long> ids, Category category, boolean confirm, boolean always) {}

    private final BankStore store;
    private final CurrentUser currentUser;

    public ReviewService(BankStore store, CurrentUser currentUser) {
        this.store = store;
        this.currentUser = currentUser;
    }

    public List<Group> groups(String importId) {
        String user = currentUser.id();
        Map<String, List<BankStore.Tx>> by = new LinkedHashMap<>();
        for (BankStore.Tx t : store.incomingOfImport(user, importId)) {
            String payer = Classifier.payerKey(t.ibanHash(), t.name());
            by.computeIfAbsent((payer == null ? "?" : payer) + "|" + t.category(), k -> new ArrayList<>()).add(t);
        }
        List<Group> out = new ArrayList<>();
        for (List<BankStore.Tx> list : by.values()) {
            BankStore.Tx first = list.get(0);
            double conf = list.stream().mapToDouble(BankStore.Tx::confidence).min().orElse(0);
            boolean confirmed = list.stream().allMatch(BankStore.Tx::confirmed);
            Bucket b = conf < 0.5 ? Bucket.DECISION : conf < 0.85 ? Bucket.SUGGESTION : Bucket.AUTO;
            out.add(new Group(0, first.name() == null || first.name().isBlank() ? "(mokėtojas nenurodytas)" : first.name(),
                    list.size(), list.stream().map(BankStore.Tx::amount).reduce(BigDecimal.ZERO, BigDecimal::add),
                    first.category(), conf, first.reason(), list.stream().map(BankStore.Tx::id).toList(), confirmed, b));
        }
        // neapsispręsta viršuje; likusieji pagal sumą
        out.sort((a, c) -> a.confirmed() != c.confirmed() ? (a.confirmed() ? 1 : -1)
                : a.bucket() != c.bucket() ? a.bucket().compareTo(c.bucket()) : c.total().compareTo(a.total()));
        List<Group> indexed = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            Group g = out.get(i);
            indexed.add(new Group(i, g.payer(), g.count(), g.total(), g.category(), g.confidence(), g.reason(), g.ids(),
                    g.confirmed(), g.bucket()));
        }
        return indexed;
    }

    /** Taiko sprendimus; operacijos, nepriklausančios vartotojui, ignoruojamos (store filtruoja pagal user_id). */
    @Transactional
    public int apply(List<Decision> decisions) {
        String user = currentUser.id();
        int confirmed = 0;
        for (Decision d : decisions) {
            if (d.ids().isEmpty() || d.category() == Category.UNKNOWN) continue;
            if (d.confirm()) {
                store.confirm(user, d.ids(), d.category());
                confirmed += d.ids().size();
            }
            if (d.always()) {
                store.tx(user, d.ids().get(0)).ifPresent(t -> createRule(user, t, d.category()));
            }
        }
        return confirmed;
    }

    private void createRule(String user, BankStore.Tx t, Category category) {
        String type = t.ibanHash() != null ? Classifier.RULE_IBAN : Classifier.RULE_NAME;
        String value = t.ibanHash() != null ? t.ibanHash() : Text.normalize(t.name());
        if (value.isEmpty()) return;
        long ruleId = store.saveRule(user, type, value, t.name(), category);
        String key = Classifier.payerKey(t.ibanHash(), t.name());
        for (BankStore.Tx o : store.unconfirmedIncoming(user)) {
            if (key != null && key.equals(Classifier.payerKey(o.ibanHash(), o.name()))) {
                store.applyRule(user, o.id(), ruleId, category);
            }
        }
    }
}
