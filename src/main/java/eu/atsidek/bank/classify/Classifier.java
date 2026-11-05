package eu.atsidek.bank.classify;

import eu.atsidek.bank.Category;
import eu.atsidek.bank.Text;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministinės taisyklės be ML: pirma suveikusi nustato kategoriją ir pasitikėjimą, todėl rezultatą
 * visada galima paaiškinti. Taikoma tik įeinančioms operacijoms.
 * Tvarka: vartotojo taisyklė, vidinis pervedimas, istorija, darbo užmokestis, asmeninės, sąskaita, juridinis asmuo.
 */
@Component
public class Classifier {

    public record Rule(long id, String matchType, String matchValue, Category category) {}

    public record Input(String counterpartyName, String counterpartyIbanHash, String remittance, String structuredRef) {}

    public record Result(Category category, double confidence, String reason, Long ruleId) {}

    /** Visa, ko reikia sprendimui: vartotojo taisyklės, jo sąskaitos ir anksčiau patvirtinti mokėtojai. */
    public record Context(List<Rule> rules, Set<String> ownIbanHashes, Set<String> confirmedIncomePayers) {}

    public static final String RULE_IBAN = "IBAN_HASH";
    public static final String RULE_NAME = "NAME";

    private final ClassifierProperties props;

    public Classifier(ClassifierProperties props) {
        this.props = props;
    }

    /** Mokėtojo raktas taisyklėms ir istorijai: IBAN maiša, jei yra, kitaip normalizuotas vardas. */
    public static String payerKey(String ibanHash, String name) {
        if (ibanHash != null) return "i:" + ibanHash;
        String n = Text.normalize(name);
        return n.isEmpty() ? null : "n:" + n;
    }

    public Result classify(Input in, Context ctx) {
        String name = Text.normalize(in.counterpartyName());
        String rem = Text.normalize(in.remittance());
        String both = (name + " " + rem).trim();

        for (Rule r : ctx.rules()) {
            boolean hit = RULE_IBAN.equals(r.matchType())
                    ? in.counterpartyIbanHash() != null && in.counterpartyIbanHash().equals(r.matchValue())
                    : !name.isEmpty() && name.equals(r.matchValue());
            if (hit) return new Result(r.category(), 1.0, "Pagal jūsų taisyklę: " + r.category().label(), r.id());
        }
        if (in.counterpartyIbanHash() != null && ctx.ownIbanHashes().contains(in.counterpartyIbanHash())) {
            return new Result(Category.INTERNAL_TRANSFER, 0.95, "Mokėtojas yra jūsų kita sąskaita", null);
        }
        String user = Text.normalize(props.userName());
        if (!user.isEmpty() && name.equals(user) && containsAny(rem, props.internalKeywords())) {
            return new Result(Category.INTERNAL_TRANSFER, 0.95, "Pervedimas nuo jūsų vardo kaip „savo sąskaita“", null);
        }
        String payer = payerKey(in.counterpartyIbanHash(), in.counterpartyName());
        if (payer != null && ctx.confirmedIncomePayers().contains(payer)) {
            return new Result(Category.BUSINESS_INCOME, 0.9, "Šį mokėtoją anksčiau patvirtinote kaip pajamas", null);
        }
        if (containsAny(rem, props.salaryKeywords())) {
            return new Result(Category.SALARY, 0.8, "Paskirtyje paminėtas darbo užmokestis", null);
        }
        if (containsAny(both, props.institutions()) || containsAny(rem, props.personalKeywords())) {
            return new Result(Category.PERSONAL, 0.7, "Panašu į grąžinimą, dovaną ar mokėjimą iš institucijos", null);
        }
        if (in.structuredRef() != null || containsAny(rem, props.invoiceKeywords())) {
            return new Result(Category.BUSINESS_INCOME, 0.85, "Paskirtyje yra sąskaitos požymis", null);
        }
        if (containsAny(name, props.legalForms())) {
            return new Result(Category.BUSINESS_INCOME, 0.75, "Mokėtojas yra juridinis asmuo", null);
        }
        return new Result(Category.UNKNOWN, 0.0, "Nepavyko nustatyti, pasirinkite patys", null);
    }

    /** Raktažodis sutampa žodžio pradžioje; trumpi (iki 3 raidžių) – tik kaip visas žodis. */
    static boolean containsAny(String normalizedText, List<String> keywords) {
        if (normalizedText.isEmpty()) return false;
        for (String k : keywords) {
            String kw = Text.normalize(k);
            if (kw.isEmpty()) continue;
            String re = "(?<![a-z0-9])" + Pattern.quote(kw) + (kw.length() <= 3 ? "(?![a-z0-9])" : "");
            if (Pattern.compile(re).matcher(normalizedText).find()) return true;
        }
        return false;
    }
}
