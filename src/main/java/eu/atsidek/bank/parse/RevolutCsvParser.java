package eu.atsidek.bank.parse;

import eu.atsidek.bank.Direction;
import eu.atsidek.bank.NormalizedTransaction;
import eu.atsidek.bank.Text;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Revolut CSV (asmeninis ir verslo eksportas). Stulpelių pavadinimai paimti iš viešai žinomų eksporto formų;
 * prieš viešą paleidimą sutikrinti su realiais nuasmenintais failais (žr. PAPILDYNYS-01, 4.2).
 */
@Component
public class RevolutCsvParser implements StatementParser {

    public static final String BANK = "Revolut";
    private static final String[] DATE = {"completed date", "date completed (utc)", "date completed",
            "started date", "date started (utc)", "date started"};

    @Override
    public boolean supports(byte[] head, String fileName) {
        List<String> h = CsvTable.headerOf(head).stream().map(Text::normalize).toList();
        boolean hasDate = Arrays.stream(DATE).anyMatch(h::contains);
        return hasDate && h.contains("amount") && h.contains("state") && h.contains("description");
    }

    @Override
    public ParseResult parse(InputStream in) throws IOException {
        CsvTable t = CsvTable.parse(in.readAllBytes());
        List<NormalizedTransaction> out = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        int skipped = 0;
        for (CsvTable.Row row : t.rows()) {
            try {
                String state = t.get(row, "state");
                if (state == null || !"completed".equalsIgnoreCase(state)) {
                    skipped++;
                    continue;
                }
                BigDecimal signed = Text.parseAmount(t.get(row, "amount"));
                String ccy = t.get(row, "currency", "payment currency");
                if (!"EUR".equalsIgnoreCase(ccy)) {
                    errors.add(new RowError(row.line(), "Palaikomos tik EUR operacijos (rasta " + ccy + ")"));
                    continue;
                }
                Direction dir = signed.signum() >= 0 ? Direction.IN : Direction.OUT;
                String desc = t.get(row, "description");
                String payer = t.get(row, "payer");
                String name = payer != null ? payer
                        : desc == null ? null : desc.replaceFirst("(?i)^payment from\\s+", "");
                String ref = t.get(row, "reference");
                out.add(new NormalizedTransaction(null,
                        Text.parseDate(t.get(row, DATE)), signed.abs(), "EUR", dir,
                        name, Text.normalizeIban(t.get(row, "beneficiary iban")),
                        ref != null ? ref : desc, null, t.get(row, "id"), null, "revolut-csv"));
            } catch (IllegalArgumentException e) {
                errors.add(new RowError(row.line(), e.getMessage()));
            }
        }
        return new ParseResult("revolut-csv", BANK, out, errors, skipped, t.rows().size());
    }
}
