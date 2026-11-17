package eu.atsidek.goals.plan;

import eu.atsidek.entitlement.EntitlementService;
import eu.atsidek.entitlement.Feature;
import eu.atsidek.goals.Assumptions;
import eu.atsidek.goals.GoalInput;
import eu.atsidek.goals.GoalMode;
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
import java.util.Map;
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
    private static final BigDecimal PERCENT_SCALE = BigDecimal.valueOf(100);

    /**
     * {@code GoalsService.validate()} klaidų pranešimai vardina {@link GoalInput}'o vidinius
     * camelCase laukus (be {@code goal.}/{@code assumptions.} priešdėlio) - tai TIKSLIAI tinka
     * {@code /goals/calculate}, kurio savas DTO naudoja tuos pačius camelCase vardus, bet NETINKA šiam
     * endpoint'ui, kurio viešas kontraktas yra {@code SavingsPlanRequest} snake_case laukai (review
     * BLOCKER - platesnis tos pačios klasės bug'as, kaip ir anksčiau pataisytas {@code income}/
     * {@code net_monthly_income} nutekėjimas, tik čia apimami VISI {@code GoalsService.validate()}
     * galimi laukų vardai, ne tik viena šaka). Verčiama PO delegavimo (žr. {@link #plan}), nes
     * dubliuoti visą {@code GoalsService.validate()} logiką čia (su savo ribomis, pvz. {@code
     * MAX_YEARS}/{@code MAX_AGE}) sukurtų antrą tiesos šaltinį, kurį reikėtų sinchronizuoti ranka -
     * vertimo lentelė yra siauresnė, lengviau peržiūrima pokyčio rizika (tik pavadinimai, ne ribos).
     * {@code income} čia defensyviai įtrauktas, nors nepasiekiamas - B6 incident pataisa (žr.
     * {@link #plan}) pašalino ankstesnį kryžminį {@code net_monthly_income}/
     * {@code monthly_tax_setaside} patikrinimą, nes {@code income} DABAR yra tiesiog
     * {@code request.netMonthlyIncome()} - NEBEprivalomas (2026-11-17 owner sprendimas, žr.
     * {@link #validate}), bet VISADA jau patikrintas {@link #checkAmount}, kad, jei PATEIKTAS,
     * nebūtų neigiamas - niekada nenukeliauja neigiamas iki {@code GoalsService.validate()}; įrašas
     * paliktas, kad naujas patikrinimas tyliai nenutekėtų, jei šis apribojimas kada pasikeistų.
     * {@code expenses} TAI PRIEŠINGAI YRA REALIAI PASIEKIAMAS (review finding, round 2): kai
     * {@code monthly_expenses} praleistas, reikšmę skaičiuoja {@link #resolveMonthlyExpenses} per
     * {@code StatDefaultsService.resolve()} (statistika) - ta reikšmė NEIŠVENGIA {@link #checkAmount}
     * patikrinimo (jis tikrina tik {@code request.monthlyExpenses()}, kuris tuomet yra {@code null}),
     * todėl bloga/ribas viršijanti {@code stat_defaults} eilutė realiai pasiekia
     * {@code GoalsService.validate()}'o {@code expenses} tikrinimą - žr.
     * {@code statisticsResolvedExpensesOutOfBoundsIsRejectedNamingRealRequestFieldNotInternalExpenses}
     * testą {@code SavingsPlanServiceTest} - šis įrašas NĖRA negyvas kodas, jo NEGALIMA pašalinti.
     */
    private static final Map<String, String> ENGINE_FIELD_TO_PUBLIC_FIELD = Map.ofEntries(
            Map.entry("goalType", "goal.type"),
            Map.entry("mode", "goal.mode"),
            Map.entry("amount", "goal.amount"),
            Map.entry("years", "goal.years"),
            Map.entry("targetYear", "goal.target_year"),
            Map.entry("targetMonth", "goal.target_month"),
            Map.entry("childCurrentAge", "goal.child_current_age"),
            Map.entry("childTargetAge", "goal.child_target_age"),
            Map.entry("monthlyContribution", "goal.monthly_contribution"),
            Map.entry("income", "net_monthly_income"),
            Map.entry("expenses", "monthly_expenses"),
            Map.entry("annualReturn", "assumptions.annual_return_pct"),
            Map.entry("annualInflation", "assumptions.annual_inflation_pct"));

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
        // B6 live-eval incident (2026-11-13, žr. docs/specs/taupymo-tikslai/B6-log.log): ANKSČIAU
        // čia buvo `.subtract(nz(request.monthlyTaxSetaside()))` - bet `net_monthly_income` (B1,
        // TaxResult.netMonthlyIncome = (revenue - actualExpenses - total) / 12, patikslinta owner
        // instrukcija 2026-11-16) JAU yra grynosios pajamos PO mokesčių IR faktinių IV išlaidų, o
        // `monthly_tax_setaside` (TaxResult.monthlyReserve = total / 12) yra TA PATI
        // mokesčių suma - atimant abu, mokesčiai buvo atimami du kartus (pvz. real case
        // combined_logged_in_02: 365,07 vietoj teisingo 815,87). `monthly_tax_setaside` yra TIK
        // rodymui (jau parodyta vartotojui per mokesčių skaičiavimo žingsnį) - laisviems pinigams
        // niekada nenaudojamas. Žr. A3 kontrakto aprašą (PLAN-taupymas-agentas.md) dėl formulės.
        //
        // Owner sprendimas, 2026-11-17 (po pirmo tikro staging patikrinimo): SĄMONINGAI NE nz(...)
        // čia - request.netMonthlyIncome() keliauja į GoalInput/Household NEPAKEISTAS (gali būti
        // null = "nenurodytas"), kad GoalsEngine galėtų atskirti "nežinoma" nuo "žinoma, 0" (žr.
        // jo komentarą) - su nz() visi "nenurodyta" atvejai taptų neteisingu NO_FREE_CASH.
        BigDecimal income = request.netMonthlyIncome();

        GoalInput goalInput = toGoalInput(request, income, monthlyExpenses);
        GoalResult result = calculate(goalInput);

        addAssumptionIfNotUserProvided(assumptions, "annual_return_pct", result, "annualReturn");
        addAssumptionIfNotUserProvided(assumptions, "annual_inflation_pct", result, "annualInflation");

        Core core = buildCore(result, assumptions, goalInput.mode());

        List<Feature> locked = lockedFeatures(currentUserId);
        Tier tier = deriveTier(locked);
        Premium premium = currentUserId != null ? new Premium() : null;

        return new SavingsPlanResponse(tier, core, locked, premium);
    }

    /**
     * Tas pats {@code locked_features} sąrašas, kurį grąžina {@link #plan}, bet be pilno
     * {@code SavingsPlanRequest} (A5, PLAN-taupymas-agentas.md A0 sprendimas #1: UI kvietimas
     * prisijungti generuojamas iš TO PAČIO serviso, ne atskiros logikos kopijos) - naudoja
     * {@code eu.atsidek.goals.plan.web.LockedFeaturesController}, kurį kviečia
     * {@code taupymo-tikslai.html} puslapio JS.
     *
     * @param currentUserId prisijungusio vartotojo id arba {@code null} anonimui (ta pati sutartis kaip {@link #plan})
     */
    public List<Feature> lockedFeatures(UUID currentUserId) {
        return GATED_FEATURES.stream().filter(f -> !hasAccess(currentUserId, f)).toList();
    }

    /**
     * Ta pati tier'o taisyklė kaip {@link #plan} (review round 1 NIT #4: anksčiau ta pati
     * ternarinė išraiška buvo dubliuota {@code eu.atsidek.chat.ChatController}'yje - abu kvietimo
     * vietos dabar naudoja TĄ PATĮ metodą, kad taisyklė negalėtų nebesutapti).
     */
    public static Tier deriveTier(List<Feature> lockedFeatures) {
        return lockedFeatures.isEmpty() ? Tier.PREMIUM : Tier.FREE;
    }

    /**
     * Kviečia {@code GoalsService.calculate()} ir, jei tas atmeta su 400, verčia pranešimo vidinius
     * {@link GoalInput} laukų vardus į tikruosius {@code SavingsPlanRequest} snake_case laukus (žr.
     * {@link #ENGINE_FIELD_TO_PUBLIC_FIELD}) - kitaip klientas matytų laukus, kurių jo užklausoje
     * NĖRA (review BLOCKER).
     */
    private GoalResult calculate(GoalInput goalInput) {
        try {
            return goalsService.calculate(goalInput);
        } catch (ResponseStatusException e) {
            throw new ResponseStatusException(e.getStatusCode(), translateEngineFieldNames(e.getReason()), e);
        }
    }

    private static String translateEngineFieldNames(String reason) {
        if (reason == null) {
            return null;
        }
        String translated = reason;
        for (var entry : ENGINE_FIELD_TO_PUBLIC_FIELD.entrySet()) {
            translated = translated.replaceAll("\\b" + entry.getKey() + "\\b", entry.getValue());
        }
        return translated;
    }

    private boolean hasAccess(UUID userId, Feature feature) {
        return userId != null && entitlementService.hasFeature(userId, feature);
    }

    private void validate(SavingsPlanRequest request, List<String> errors) {
        if (request.goal() == null) {
            errors.add("goal privalomas");
        }
        // Owner sprendimas, 2026-11-17 (po pirmo tikro staging patikrinimo): net_monthly_income
        // NEBĖRA privalomas čia (anksčiau 400, jei nenurodytas) - PLAN.md principas "pajamos
        // niekada neimamos iš statistikos/nespėjamos" IŠLIEKA (net_monthly_income tebelieka tik iš
        // vartotojo paties nurodyto skaičiaus arba ankstesnio mokesčių skaičiavimo), bet trūkstamos
        // pajamos dabar reiškia "skaičiuok įmoką/datą be laisvų pinigų įvertinimo", ne atmetimą -
        // žr. GoalsEngine#calculate komentarą ir SavingsPlanService#buildCore.
        checkAmount("net_monthly_income", request.netMonthlyIncome(), errors);
        checkAmount("monthly_tax_setaside", request.monthlyTaxSetaside(), errors);
        checkAmount("monthly_expenses", request.monthlyExpenses(), errors);
        // B6 incident pataisa pašalino ankstesnį "net_monthly_income turi būti bent lygus
        // monthly_tax_setaside" kryžminį patikrinimą - jis gynė VIDINĮ income = net_monthly_income
        // - monthly_tax_setaside skaičiavimą, kurio DABAR nebėra (monthly_tax_setaside tik
        // rodymui, žr. #plan). Tas patikrinimas pats savaime neturėjo prasmės: TaxResult'e
        // netMonthlyIncome + monthlyReserve = revenue/12, todėl netMonthlyIncome < monthlyReserve
        // yra visiškai normalus atvejis (efektyvus mokesčių tarifas > 50 %), ne klaida.
    }

    private static void checkAmount(String name, BigDecimal v, List<String> errors) {
        if (v == null) {
            return;
        }
        if (v.signum() < 0) {
            errors.add(name + " negali būti neigiamas");
        } else if (v.compareTo(GoalsService.MAX_AMOUNT) > 0) {
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
                expenses.illustrative(), expenses.needsReview()));
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
                    resolved.source(), resolved.asOf(), null, null));
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

    private static Core buildCore(GoalResult result, List<AssumptionEntry> assumptions, GoalMode mode) {
        // Owner sprendimas, 2026-11-17: result.verdict() yra null tiksliai tada, kai pajamos
        // nenurodytos (žr. GoalsEngine#calculate) - fits/sharePct/generalTip tada taip pat null
        // ("nežinoma"), ne false/0/verdikto patarimas, kad klientas (čia - Python response_builder)
        // negalėtų supainioti "nežinoma" su "žinoma, bet netelpa".
        Verdict verdict = result.verdict();
        Boolean fits = verdict == null ? null : verdict != Verdict.UNREALISTIC && verdict != Verdict.NO_FREE_CASH;
        BigDecimal sharePct = result.share() == null ? null
                : result.share().multiply(PERCENT_SCALE).setScale(2, RoundingMode.HALF_UP);
        return new Core(result.monthly(), result.freeCash(), sharePct, fits, generalTip(verdict, mode),
                List.copyOf(assumptions), DISCLAIMER, result.reachable(), result.reachDate());
    }

    /**
     * Deterministiniai šablonai pagal {@code GoalsEngine.Verdict} ribas (NE AI tekstas, žr. PLAN.md A3).
     * <p>
     * Owner instrukcija (2026-11-16, po eval'o a2dbaae, 4b punktas): WHEN režimui "apsvarstykite ilgesnį
     * terminą" patarimas neturi prasmės - WHEN pats suranda datą fiksuotai įmokai, terminas yra IŠVESTIS,
     * ne įvestis, kurią būtų galima "pailginti". WHEN režimui vietoj to patariama mažesnė įmoka.
     * <p>
     * Owner instrukcija (2026-11-16, po eval'o fae9e00, 2 punktas): dėl tos pačios priežasties UNREALISTIC
     * atveju "su šiuo terminu tikslas greičiausiai nerealus" taip pat netinka WHEN režimui (terminas - ne
     * vartotojo įvestis) - WHEN režimui šis "terminu" žodis taip pat pašalinamas.
     */
    private static String generalTip(Verdict verdict, GoalMode mode) {
        // Owner sprendimas, 2026-11-17: verdict null ("pajamos nenurodytos", žr. #buildCore) ->
        // kvietimas nurodyti pajamas, NE verdikto patarimas (nėra verdikto, kurį patarti pagal).
        if (verdict == null) {
            return "Jei norite sužinoti, ar tai telpa į biudžetą, nurodykite savo pajamas.";
        }
        String adjustAdvice = mode == GoalMode.WHEN
                ? "apsvarstykite mažesnę įmoką"
                : "apsvarstykite ilgesnį terminą arba mažesnę sumą";
        String unrealisticClause = mode == GoalMode.WHEN
                ? "tikslas greičiausiai nerealus"
                : "su šiuo terminu tikslas greičiausiai nerealus";
        return switch (verdict) {
            case NO_FREE_CASH -> "Laisvų pinigų nėra arba jie neigiami – pirmiausia peržiūrėkite išlaidas, "
                    + "kad atsirastų, iš ko taupyti.";
            case COMFORTABLE -> "Šis tikslas atrodo pasiekiamas – įmoka sudaro nedidelę dalį jūsų laisvų pinigų.";
            case TIGHT -> "Šis tikslas pasiekiamas, bet pareikalaus nemažos dalies laisvų pinigų – "
                    + adjustAdvice + ".";
            case UNREALISTIC -> "Reikalinga įmoka viršija pusę jūsų laisvų pinigų – " + unrealisticClause
                    + ", " + adjustAdvice + ".";
        };
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static int nzInt(Integer v) {
        return v == null ? 0 : v;
    }
}
