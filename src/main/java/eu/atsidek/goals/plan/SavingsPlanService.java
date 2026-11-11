package eu.atsidek.goals.plan;

import eu.atsidek.entitlement.EntitlementService;
import eu.atsidek.entitlement.Feature;
import eu.atsidek.goals.Assumptions;
import eu.atsidek.goals.GoalInput;
import eu.atsidek.goals.GoalResult;
import eu.atsidek.goals.Household;
import eu.atsidek.goals.Origin;
import eu.atsidek.goals.Verdict;
import eu.atsidek.goals.plan.SavingsPlanRequest.AssumptionsRequest;
import eu.atsidek.goals.plan.SavingsPlanRequest.GoalRequest;
import eu.atsidek.goals.plan.SavingsPlanRequest.HouseholdRequest;
import eu.atsidek.goals.stats.StatDefaultValue;
import eu.atsidek.goals.stats.StatDefaultsService;
import eu.atsidek.goals.web.GoalsService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Vienintelis šaltinis tier'o/{@code core}/{@code premium}/{@code locked_features} logikai
 * (PLAN-taupymas-agentas.md A0 sprendimas #1) - naudoja ir {@code SavingsPlanApiController} (A3), ir
 * ateityje (A5) UI. Skaičiai visada iš {@code GoalsEngine} (per {@link GoalsService}, kad validacija
 * nebūtų dubliuojama) - šis servisas tik verčia snake_case užklausą/atsakymą ir sprendžia, kas kam
 * matoma.
 */
@Service
public class SavingsPlanService {

    /**
     * Fiksuota tvarka (ne {@code Feature} deklaravimo eilė) - sutampa su PLAN-taupymas-agentas.md A3
     * pavyzdžio atsakymu. {@code SAVINGS_IRREGULAR_INCOME} SĄMONINGAI nėra (A0 sprendimas #4) -
     * naudojamas esamas {@code SAVINGS_IV_LINK}.
     */
    private static final List<Feature> GATED_FEATURES = List.of(Feature.SAVINGS_MULTI_GOAL, Feature.SAVINGS_IV_LINK,
            Feature.SAVINGS_SCENARIOS);

    private static final String DISCLAIMER = "Skaičiavimai informaciniai, tai nėra finansinis ar investavimo patarimas.";
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000");
    private static final BigDecimal PERCENT_SCALE = BigDecimal.valueOf(100);

    private final EntitlementService entitlementService;
    private final StatDefaultsService statDefaultsService;
    private final GoalsService goalsService = new GoalsService();

    public SavingsPlanService(EntitlementService entitlementService, StatDefaultsService statDefaultsService) {
        this.entitlementService = entitlementService;
        this.statDefaultsService = statDefaultsService;
    }

    /**
     * @param currentUserId prisijungusio vartotojo id arba {@code null} anonimui (PLAN.md A4
     *                      sprendimas #6: tier sprendžiamas tik pagal sesiją, be Python→Java
     *                      autentifikacijos - ta antraštė perkelta į B1). Sąmoningai priimamas kaip
     *                      paprastas parametras, ne skaitomas tiesiai iš {@code SecurityContextHolder}
     *                      čia - tai palieka šį servisą testuojamą be Spring Security/HTTP konteksto.
     */
    public SavingsPlanResponse plan(SavingsPlanRequest request, UUID currentUserId) {
        List<String> errors = new ArrayList<>();
        validate(request, errors);
        if (!errors.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", errors));
        }

        List<AssumptionEntry> assumptions = new ArrayList<>();
        BigDecimal monthlyExpenses = resolveMonthlyExpenses(request, assumptions);
        BigDecimal income = nz(request.netMonthlyIncome()).subtract(nz(request.monthlyTaxSetaside()));

        GoalInput goalInput = toGoalInput(request, income, monthlyExpenses);
        GoalResult result = goalsService.calculate(goalInput);

        addAssumptionIfNotUserProvided(assumptions, "annual_return_pct", result, "annualReturn");
        addAssumptionIfNotUserProvided(assumptions, "annual_inflation_pct", result, "annualInflation");

        Core core = buildCore(result, assumptions);

        List<Feature> locked = GATED_FEATURES.stream().filter(f -> !hasAccess(currentUserId, f)).toList();
        Tier tier = locked.isEmpty() ? Tier.PREMIUM : Tier.FREE;
        Premium premium = currentUserId != null ? new Premium() : null;

        return new SavingsPlanResponse(tier, core, locked, premium);
    }

    private boolean hasAccess(UUID userId, Feature feature) {
        return userId != null && entitlementService.hasFeature(userId, feature);
    }

    private void validate(SavingsPlanRequest request, List<String> errors) {
        if (request.goal() == null) {
            errors.add("goal privalomas");
        }
        if (request.netMonthlyIncome() == null) {
            // PLAN.md principas: pajamos niekada neimamos iš statistikos; čia (sinchroninė API, be
            // agento ciklo) nėra kam paklausti, todėl aiškus 400, ne spėjimas.
            errors.add("net_monthly_income privalomas - be jo neskaičiuojami laisvi pinigai, "
                    + "o pajamų niekada negalima spėti iš statistikos");
        }
        checkAmount("net_monthly_income", request.netMonthlyIncome(), errors);
        checkAmount("monthly_tax_setaside", request.monthlyTaxSetaside(), errors);
        checkAmount("monthly_expenses", request.monthlyExpenses(), errors);
        // Patikrinta PRIEŠ delegavimą į GoalsService.calculate() (review rastas bug'as): income =
        // net_monthly_income - monthly_tax_setaside yra VIDINIS laukas, kurio GoalsService.validate()
        // nežino nieko apie SavingsPlanRequest schemą - jei šis skirtumas neigiamas, GoalsService
        // grąžintų "income negali būti neigiamas", o "income" NĖRA viešos užklausos lauko vardas
        // (tikrieji laukai - net_monthly_income/monthly_tax_setaside), todėl klientas negalėtų
        // suprasti, kurį iš jų taisyti. Tikrinama ČIA, PRIEŠ skaičiavimą, kad klaida įvardintų
        // tikruosius laukus.
        if (request.netMonthlyIncome() != null && request.monthlyTaxSetaside() != null
                && nz(request.netMonthlyIncome()).subtract(nz(request.monthlyTaxSetaside())).signum() < 0) {
            errors.add("net_monthly_income turi būti bent lygus monthly_tax_setaside");
        }
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

    /** Statistika kviečiama TIK kai {@code monthly_expenses} praleistas - vartotojo reikšmė niekada neperrašoma. */
    private BigDecimal resolveMonthlyExpenses(SavingsPlanRequest request, List<AssumptionEntry> assumptions) {
        if (request.monthlyExpenses() != null) {
            return request.monthlyExpenses();
        }
        HouseholdRequest household = request.household();
        Integer members = household.adults() == null && household.children() == null ? null
                : nzInt(household.adults()) + nzInt(household.children());
        Integer workers = household.adults();
        String goalType = request.goal() == null || request.goal().type() == null ? null
                : request.goal().type().name();

        StatDefaultValue expenses = statDefaultsService.resolve(members, workers, null, goalType).expenses();
        assumptions.add(new AssumptionEntry("monthly_expenses", expenses.value(), Origin.STATISTICS,
                expenses.source(), expenses.asOf() == null ? null : expenses.asOf().toString(),
                expenses.illustrative()));
        return expenses.value();
    }

    /**
     * {@code annualReturn}/{@code annualInflation} patenka į {@link AssumptionEntry} sąrašą TIK kai
     * {@code GoalsEngine} jiems pritaikė numatytąją reikšmę ({@code Origin.ASSUMPTION}) - vartotojo
     * pačio įvestos reikšmės ({@code Origin.USER}) čia nerodomos (sprendimas: {@code core.assumptions}
     * rodo tik tai, ko vartotojas NEnurodė).
     */
    private static void addAssumptionIfNotUserProvided(List<AssumptionEntry> assumptions, String field,
                                                         GoalResult result, String resolvedKey) {
        var resolved = result.resolvedInputs().get(resolvedKey);
        if (resolved != null && resolved.origin() == Origin.ASSUMPTION) {
            assumptions.add(new AssumptionEntry(field, (BigDecimal) resolved.value(), Origin.ASSUMPTION,
                    resolved.source(), resolved.asOf(), null));
        }
    }

    private static GoalInput toGoalInput(SavingsPlanRequest request, BigDecimal income, BigDecimal expenses) {
        GoalRequest goal = request.goal();
        HouseholdRequest household = request.household();
        AssumptionsRequest assumptionsRequest = request.assumptions();
        Integer members = household.adults() == null && household.children() == null ? null
                : nzInt(household.adults()) + nzInt(household.children());

        return new GoalInput(goal.type(), goal.mode(), goal.amount(), goal.years(), goal.targetYear(),
                goal.targetMonth(), goal.childCurrentAge(), goal.childTargetAge(), goal.monthlyContribution(),
                new Household(members, household.adults(), income, expenses, null),
                new Assumptions(assumptionsRequest.annualReturnPct(), assumptionsRequest.annualInflationPct()),
                null);
    }

    private static Core buildCore(GoalResult result, List<AssumptionEntry> assumptions) {
        boolean fits = result.verdict() != Verdict.UNREALISTIC && result.verdict() != Verdict.NO_FREE_CASH;
        BigDecimal sharePct = result.share().multiply(PERCENT_SCALE).setScale(2, RoundingMode.HALF_UP);
        return new Core(result.monthly(), result.freeCash(), sharePct, fits, generalTip(result.verdict()),
                List.copyOf(assumptions), DISCLAIMER);
    }

    /** Deterministiniai šablonai pagal {@code GoalsEngine.Verdict} ribas (NE AI tekstas, žr. PLAN.md A3). */
    private static String generalTip(Verdict verdict) {
        return switch (verdict) {
            case NO_FREE_CASH -> "Laisvų pinigų nėra arba jie neigiami – pirmiausia peržiūrėkite išlaidas, "
                    + "kad atsirastų, iš ko taupyti.";
            case COMFORTABLE -> "Šis tikslas atrodo pasiekiamas – įmoka sudaro nedidelę dalį jūsų laisvų pinigų.";
            case TIGHT -> "Šis tikslas pasiekiamas, bet pareikalaus nemažos dalies laisvų pinigų – "
                    + "apsvarstykite ilgesnį terminą arba mažesnę sumą.";
            case UNREALISTIC -> "Reikalinga įmoka viršija pusę jūsų laisvų pinigų – su šiuo terminu tikslas "
                    + "greičiausiai nerealus, apsvarstykite ilgesnį terminą arba mažesnę sumą.";
        };
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static int nzInt(Integer v) {
        return v == null ? 0 : v;
    }
}
