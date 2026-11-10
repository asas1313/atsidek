package eu.atsidek.goals.web;

import eu.atsidek.goals.GoalInput;
import eu.atsidek.goals.GoalResult;
import eu.atsidek.goals.GoalsEngine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bendra validacija ir skaičiavimo užklausimas abiems kontroleriams ({@code /goals/calculate} ir
 * {@code /api/v1/goals/calculate}), kad validacijos/orkestravimo logika nebūtų dubliuojama (PLAN.md K2).
 */
public class GoalsService {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000");
    private static final BigDecimal MAX_RATE = new BigDecimal("100");
    private static final int MAX_YEARS = 100;
    private static final int MAX_AGE = 130;

    private final GoalsEngine engine = new GoalsEngine();

    public GoalResult calculate(GoalInput in) {
        List<String> errors = new ArrayList<>();
        validate(in, errors);
        if (!errors.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", errors));
        }
        return engine.calculate(in);
    }

    private void validate(GoalInput in, List<String> errors) {
        if (in.goalType() == null) {
            errors.add("goalType privalomas");
        }
        if (in.mode() == null) {
            errors.add("mode privalomas");
            return; // be mode negalima patikrinti su juo susijusių termino laukų
        }
        if (in.amount() == null) {
            errors.add("amount privalomas");
        }
        checkAmount("amount", in.amount(), errors);

        switch (in.mode()) {
            case YEARS -> checkYears(in, errors);
            case DATE -> checkDate(in, errors);
            case CHILD_AGE -> checkChildAge(in, errors);
            case WHEN -> checkWhen(in, errors);
        }

        checkAmount("income", in.household().income(), errors);
        checkAmount("expenses", in.household().expenses(), errors);
        checkRate("annualReturn", in.assumptions().annualReturn(), errors);
        checkRate("annualInflation", in.assumptions().annualInflation(), errors);
    }

    private static void checkYears(GoalInput in, List<String> errors) {
        if (in.years() == null) {
            errors.add("years privalomas, kai mode = YEARS");
        } else if (in.years() <= 0 || in.years() > MAX_YEARS) {
            errors.add("years turi būti 1–" + MAX_YEARS);
        }
    }

    private static void checkDate(GoalInput in, List<String> errors) {
        if (in.targetYear() == null) {
            errors.add("targetYear privalomas, kai mode = DATE");
        }
        if (in.targetMonth() != null && (in.targetMonth() < 1 || in.targetMonth() > 12)) {
            errors.add("targetMonth turi būti 1–12");
        }
    }

    private static void checkChildAge(GoalInput in, List<String> errors) {
        if (in.childCurrentAge() == null || in.childTargetAge() == null) {
            errors.add("childCurrentAge ir childTargetAge privalomi, kai mode = CHILD_AGE");
            return;
        }
        if (in.childCurrentAge() < 0 || in.childCurrentAge() > MAX_AGE
                || in.childTargetAge() < 0 || in.childTargetAge() > MAX_AGE) {
            errors.add("vaiko amžius turi būti 0–" + MAX_AGE);
        } else if (in.childTargetAge() <= in.childCurrentAge()) {
            errors.add("childTargetAge turi būti didesnis už childCurrentAge");
        }
    }

    private static void checkWhen(GoalInput in, List<String> errors) {
        if (in.monthlyContribution() == null) {
            errors.add("monthlyContribution privalomas, kai mode = WHEN");
        }
        checkAmount("monthlyContribution", in.monthlyContribution(), errors);
    }

    private static void checkAmount(String name, BigDecimal v, List<String> errors) {
        if (v == null) {
            return;
        }
        if (v.signum() < 0) {
            errors.add(name + " negali būti neigiamas");
        } else if (v.compareTo(MAX_AMOUNT) > 0) {
            errors.add(name + " per didelis");
        }
    }

    private static void checkRate(String name, BigDecimal v, List<String> errors) {
        if (v == null) {
            return;
        }
        if (v.signum() < 0) {
            errors.add(name + " negali būti neigiama");
        } else if (v.compareTo(MAX_RATE) > 0) {
            errors.add(name + " per didelė");
        }
    }
}
