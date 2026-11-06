package eu.atsidek.ai;

import java.math.BigDecimal;
import java.util.List;

/** Iš laisvo teksto ištraukti duomenys (tas pats kontraktas kaip ai/istraukimas.py). */
public record Extraction(
        BigDecimal revenue,
        BigDecimal expenses,
        String expenseMethod,
        String period,
        BigDecimal otherIncome,
        Boolean pillar2,
        Integer psdMonths,
        List<String> missing) {
}
