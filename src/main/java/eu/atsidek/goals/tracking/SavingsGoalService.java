package eu.atsidek.goals.tracking;

import eu.atsidek.goals.GoalInput;
import eu.atsidek.goals.GoalResult;
import eu.atsidek.goals.GoalsEngine;
import eu.atsidek.goals.web.GoalsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Taupymo tikslo sekimo verslo logika (PLAN.md §5/§6 task 9): {@link #save} - trijų šakų elgsena
 * (nėra tikslo → kuria; yra + nepatvirtinta → nieko nekeičia, grąžina patvirtinimo užklausimą; yra +
 * patvirtinta → ištrina senas įmokas IR atnaujina tikslą TAME PAČIAME tranzakcijoje). Entitlement
 * patikra ({@code EntitlementService.hasFeature}) atliekama kviečiančiame valdiklyje (task 10), ne
 * čia - §5 skiria "ar funkcija turima" (kolekcijos lygmuo) nuo "ar šis konkretus įrašas tavo"
 * (savininkystė, §7). Progresas perskaičiuoja {@code GoalsEngine} formulę nuo {@code goal.createdAt()}
 * iki dabar (įpurškiamas {@link Clock}), kad planas (mėnesinė įmoka, terminas) liktų stabilus.
 */
@Service
public class SavingsGoalService {

    /** {@code HttpSession} rakto pavadinimas laukiančiam (dar neišsaugotam) skaičiavimui - §5/§6 task 11. */
    public static final String PENDING_INPUT_SESSION_KEY = "pendingSavingsGoalInput";

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000");
    private static final BigDecimal RATE_TO_MONTHLY_DIVISOR = BigDecimal.valueOf(1200);
    private static final int CALC_SCALE = 20;

    private final SavingsGoalStore store;
    private final Clock clock;
    private final GoalsService goalsService = new GoalsService();
    private final GoalsEngine engine = new GoalsEngine();

    public SavingsGoalService(SavingsGoalStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** @param goal {@code null}, kai {@link #needsConfirmation} - tada tik {@code existingContributionCount} prasmingas. */
    public record SaveResult(SavingsGoal goal, boolean needsConfirmation, int existingContributionCount) {

        public static SaveResult saved(SavingsGoal goal) {
            return new SaveResult(goal, false, 0);
        }

        public static SaveResult needsConfirmation(int existingContributionCount) {
            return new SaveResult(null, true, existingContributionCount);
        }
    }

    public record Progress(BigDecimal expectedAccumulated, BigDecimal actualAccumulated,
                            int elapsedMonths, int totalMonths, boolean onTrack) {
    }

    @Transactional
    public SaveResult save(UUID userId, GoalInput input, boolean confirmed) {
        GoalResult result = goalsService.calculate(input); // validacija + skaičiavimas (400, jei netinka)
        Optional<SavingsGoal> existing = store.findByUserId(userId);

        if (existing.isPresent() && !confirmed) {
            int existingContributionCount = store.countContributions(existing.get().id());
            return SaveResult.needsConfirmation(existingContributionCount);
        }
        if (existing.isPresent()) {
            // Tas pats goal.id išlieka (upsert vietoje) - senos įmokos BŪTŲ palikdintos prikabintos
            // prie pakartotinai naudojamo id, jei nebūtų ištrintos čia, toje pačioje tranzakcijoje
            // kaip ir tikslo atnaujinimas (§5 blokuojantis pataisymas).
            store.deleteContributions(existing.get().id());
        }

        LocalDate targetDate = result.months() != null ? LocalDate.now(clock).plusMonths(result.months()) : null;
        SavingsGoal saved = store.upsert(userId, input.goalType(), input.amount(), targetDate, input);
        return SaveResult.saved(saved);
    }

    public Optional<SavingsGoal> findOwnGoal(UUID userId, UUID goalId) {
        return store.findByIdAndUserId(goalId, userId);
    }

    public Optional<SavingsGoal> findOwnGoal(UUID userId) {
        return store.findByUserId(userId);
    }

    public int contributionCount(UUID goalId) {
        return store.countContributions(goalId);
    }

    public void recordContribution(SavingsGoal goal, YearMonth month, BigDecimal amount) {
        checkAmount("amount", amount);
        store.upsertContribution(goal.id(), month.atDay(1), amount);
    }

    public Progress progress(SavingsGoal goal) {
        LocalDate createdAtDate = goal.createdAt().atZone(ZoneOffset.UTC).toLocalDate();
        GoalResult result = engine.calculate(goal.input(), createdAtDate);
        int totalMonths = result.months() == null ? 0 : result.months();

        long elapsed = ChronoUnit.MONTHS.between(createdAtDate, LocalDate.now(clock));
        int elapsedMonths = (int) Math.max(0, Math.min(elapsed, totalMonths));

        BigDecimal annualReturn = goal.input().assumptions().annualReturn() != null
                ? goal.input().assumptions().annualReturn()
                : GoalsEngine.DEFAULT_ANNUAL_RETURN;
        BigDecimal rm = annualReturn.divide(RATE_TO_MONTHLY_DIVISOR, CALC_SCALE, RoundingMode.HALF_UP);
        BigDecimal expected = GoalsEngine.balance(result.monthly(), rm, elapsedMonths).setScale(2, RoundingMode.HALF_UP);

        List<SavingsContribution> contributions = store.findContributions(goal.id());
        BigDecimal actual = contributions.stream().map(SavingsContribution::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean onTrack = actual.compareTo(expected) >= 0;
        return new Progress(expected, actual, elapsedMonths, totalMonths, onTrack);
    }

    private static void checkAmount(String name, BigDecimal v) {
        if (v == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " privalomas");
        }
        if (v.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " negali būti neigiamas");
        }
        if (v.compareTo(MAX_AMOUNT) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " per didelis");
        }
    }
}
