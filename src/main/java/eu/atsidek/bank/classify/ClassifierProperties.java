package eu.atsidek.bank.classify;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Raktažodžiai konfigūracijoje (application.yml), kad juos būtų galima papildyti be išleidimo. */
@ConfigurationProperties("atsidek.classifier")
public record ClassifierProperties(
        List<String> internalKeywords,
        List<String> salaryKeywords,
        List<String> personalKeywords,
        List<String> institutions,
        List<String> invoiceKeywords,
        List<String> legalForms,
        String userName) {

    public ClassifierProperties {
        internalKeywords = internalKeywords == null ? List.of() : internalKeywords;
        salaryKeywords = salaryKeywords == null ? List.of() : salaryKeywords;
        personalKeywords = personalKeywords == null ? List.of() : personalKeywords;
        institutions = institutions == null ? List.of() : institutions;
        invoiceKeywords = invoiceKeywords == null ? List.of() : invoiceKeywords;
        legalForms = legalForms == null ? List.of() : legalForms;
    }
}
