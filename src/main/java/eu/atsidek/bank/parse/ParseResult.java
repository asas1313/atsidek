package eu.atsidek.bank.parse;

import eu.atsidek.bank.NormalizedTransaction;
import java.util.List;

/** @param skipped sąmoningai praleistos eilutės (nebaigtos / nepatvirtintos operacijos) */
public record ParseResult(String format, String bankName, List<NormalizedTransaction> transactions,
                          List<RowError> errors, int skipped, int totalRows) {}
