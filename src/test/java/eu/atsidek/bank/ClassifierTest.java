package eu.atsidek.bank;

import static org.assertj.core.api.Assertions.assertThat;

import eu.atsidek.bank.classify.Classifier;
import eu.atsidek.bank.classify.ClassifierProperties;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassifierTest {

    private final Classifier c = new Classifier(new ClassifierProperties(
            List.of("savo saskaita", "pervedimas"),
            List.of("darbo uzmokestis", "du", "atlyginimas", "salary"),
            List.of("grazinimas", "refund", "dovana", "paskola"),
            List.of("vmi", "sodra"),
            List.of("saskaita", "sf", "invoice", "pvm sf"),
            List.of("uab", "mb", "ab", "ii", "vsi", "ltd", "gmbh"),
            "Jonas Jonaitis"));

    private static final Classifier.Context EMPTY = new Classifier.Context(List.of(), Set.of(), Set.of());

    private Classifier.Result run(String name, String rem, String ref, Classifier.Context ctx) {
        return c.classify(new Classifier.Input(name, null, rem, ref), ctx);
    }

    @Test
    void diacriticsDoNotMatter() {
        assertThat(run("Petras", "Apmokėjimas pagal SĄSKAITĄ 15", null, EMPTY).category()).isEqualTo(Category.BUSINESS_INCOME);
        assertThat(run("Petras", "Apmokejimas pagal saskaita 15", null, EMPTY).confidence()).isEqualTo(0.85);
    }

    @Test
    void salaryAndShortKeywordNeedsWholeWord() {
        assertThat(run("UAB X", "Darbo užmokestis spalis", null, EMPTY).category()).isEqualTo(Category.SALARY);
        assertThat(run("Tomas", "DU už spalį", null, EMPTY).category()).isEqualTo(Category.SALARY);
        assertThat(run("Tomas", "duona", null, EMPTY).category()).isEqualTo(Category.UNKNOWN);
    }

    @Test
    void legalEntityAndPersonal() {
        assertThat(run("UAB Pavyzdys", null, null, EMPTY)).extracting(Classifier.Result::category, Classifier.Result::confidence)
                .containsExactly(Category.BUSINESS_INCOME, 0.75);
        assertThat(run("Petras", "grąžinimas už prekę", null, EMPTY).category()).isEqualTo(Category.PERSONAL);
        assertThat(run("VMI", "permoka", null, EMPTY).category()).isEqualTo(Category.PERSONAL);
    }

    @Test
    void structuredReferenceMeansInvoice() {
        assertThat(run("Petras", null, "RF1234", EMPTY).category()).isEqualTo(Category.BUSINESS_INCOME);
    }

    @Test
    void unknownWhenNothingMatches() {
        Classifier.Result r = run("Petras", "ačiū", null, EMPTY);
        assertThat(r.category()).isEqualTo(Category.UNKNOWN);
        assertThat(r.confidence()).isZero();
    }

    @Test
    void ownAccountIsInternalTransfer() {
        Classifier.Context ctx = new Classifier.Context(List.of(), Set.of("hash-own"), Set.of());
        Classifier.Result r = c.classify(new Classifier.Input("Jonas Jonaitis", "hash-own", "SF 1", null), ctx);
        assertThat(r.category()).isEqualTo(Category.INTERNAL_TRANSFER);   // net su "SF" paskirtyje
    }

    @Test
    void userRuleBeatsEverythingAndHistoryBeatsKeywords() {
        Classifier.Context rule = new Classifier.Context(
                List.of(new Classifier.Rule(7, Classifier.RULE_NAME, "petras", Category.PERSONAL)), Set.of(), Set.of());
        Classifier.Result r = run("PETRAS", "Sąskaita 1", null, rule);
        assertThat(r.category()).isEqualTo(Category.PERSONAL);
        assertThat(r.confidence()).isEqualTo(1.0);
        assertThat(r.ruleId()).isEqualTo(7L);

        Classifier.Context hist = new Classifier.Context(List.of(), Set.of(), Set.of("n:petras"));
        Classifier.Result h = run("Petras", "dovana", null, hist);
        assertThat(h.category()).isEqualTo(Category.BUSINESS_INCOME);
        assertThat(h.confidence()).isEqualTo(0.9);
    }
}
