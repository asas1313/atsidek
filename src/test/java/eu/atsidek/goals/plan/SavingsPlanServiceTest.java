package eu.atsidek.goals.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import eu.atsidek.entitlement.EntitlementService;
import eu.atsidek.entitlement.Feature;
import eu.atsidek.goals.GoalMode;
import eu.atsidek.goals.GoalType;
import eu.atsidek.goals.Origin;
import eu.atsidek.goals.plan.SavingsPlanRequest.AssumptionsRequest;
import eu.atsidek.goals.plan.SavingsPlanRequest.GoalRequest;
import eu.atsidek.goals.plan.SavingsPlanRequest.HouseholdRequest;
import eu.atsidek.goals.stats.StatDefaultValue;
import eu.atsidek.goals.stats.StatDefaultsResponse;
import eu.atsidek.goals.stats.StatDefaultsService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pure (be Spring konteksto/DB) {@link SavingsPlanService} atvejai: {@code StatDefaultsService} ir
 * {@code EntitlementService} mockinti, skaičiavimas pats - tikras {@code GoalsEngine} per
 * {@code GoalsService} (PLAN-taupymas-agentas.md A3 sprendimas #1: plonas sluoksnis, logikos
 * nedubliuoti). HTTP sluoksnio (controller'io) testai - {@code SavingsPlanApiControllerTest}.
 */
class SavingsPlanServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final List<Feature> GATED = List.of(Feature.SAVINGS_MULTI_GOAL, Feature.SAVINGS_IV_LINK,
            Feature.SAVINGS_SCENARIOS);

    private static GoalRequest yearsGoal(String amount, int years) {
        return new GoalRequest(GoalType.HOME_DOWN_PAYMENT, GoalMode.YEARS, new BigDecimal(amount), years, null, null,
                null, null, null);
    }

    private static GoalRequest whenGoal(String amount, String monthlyContribution) {
        return new GoalRequest(GoalType.CAR, GoalMode.WHEN, new BigDecimal(amount), null, null, null, null, null,
                new BigDecimal(monthlyContribution));
    }

    private static SavingsPlanRequest request(GoalRequest goal, String netIncome, String taxSetaside,
                                               String expenses) {
        return new SavingsPlanRequest(goal,
                netIncome == null ? null : new BigDecimal(netIncome),
                taxSetaside == null ? null : new BigDecimal(taxSetaside),
                expenses == null ? null : new BigDecimal(expenses),
                null, null);
    }

    private static StatDefaultsService statsResolvingExpensesTo(BigDecimal value, boolean illustrative) {
        StatDefaultsService stats = mock(StatDefaultsService.class);
        StatDefaultValue expensesValue = new StatDefaultValue(value, "EUR_PER_MONTH", "STATISTICS",
                "VDA namų ūkių biudžetų tyrimas", "https://osp.stat.gov.lt/example", LocalDate.of(2026, 1, 1),
                illustrative);
        StatDefaultsResponse response = new StatDefaultsResponse(null, expensesValue, null, null, null);
        when(stats.resolve(any(), any(), any(), any())).thenReturn(response);
        return stats;
    }

    private static EntitlementService entitlementAlwaysDenies() {
        EntitlementService entitlement = mock(EntitlementService.class);
        when(entitlement.hasFeature(any(), any())).thenReturn(false);
        return entitlement;
    }

    private static EntitlementService entitlementAlwaysGrants() {
        EntitlementService entitlement = mock(EntitlementService.class);
        when(entitlement.hasFeature(any(), any())).thenReturn(true);
        return entitlement;
    }

    @Test
    void anonymousFreeTierHasAllFeaturesLockedAndNoPremiumKey() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysGrants(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));

        SavingsPlanResponse response = service.plan(
                request(yearsGoal("15000", 5), "3100", "900", "1350"), null);

        assertThat(response.tier()).isEqualTo(Tier.FREE);
        assertThat(response.lockedFeatures()).containsExactlyElementsOf(GATED);
        assertThat(response.premium()).isNull();
    }

    @Test
    void loggedInUserWithAllFeaturesGetsPremiumTierAndPremiumKey() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysGrants(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));

        SavingsPlanResponse response = service.plan(
                request(yearsGoal("15000", 5), "3100", "900", "1350"), USER_ID);

        assertThat(response.tier()).isEqualTo(Tier.PREMIUM);
        assertThat(response.lockedFeatures()).isEmpty();
        assertThat(response.premium()).isEqualTo(new Premium());
    }

    @Test
    void loggedInUserWithoutAnyGatedFeatureStillGetsPremiumKeyButFreeTier() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));

        SavingsPlanResponse response = service.plan(
                request(yearsGoal("15000", 5), "3100", "900", "1350"), USER_ID);

        assertThat(response.tier()).isEqualTo(Tier.FREE);
        assertThat(response.lockedFeatures()).containsExactlyElementsOf(GATED);
        assertThat(response.premium()).isEqualTo(new Premium());
    }

    @Test
    void monthlyExpensesOmittedIsResolvedFromStatisticsAndSurfacedWithSource() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));

        SavingsPlanResponse response = service.plan(
                request(yearsGoal("15000", 5), "3100", "900", null), null);

        AssumptionEntry expensesEntry = response.core().assumptions().stream()
                .filter(e -> e.field().equals("monthly_expenses")).findFirst().orElseThrow();
        assertThat(expensesEntry.value()).isEqualByComparingTo("1350.00");
        assertThat(expensesEntry.origin()).isEqualTo(Origin.STATISTICS);
        assertThat(expensesEntry.source()).isEqualTo("VDA namų ūkių biudžetų tyrimas");
        assertThat(expensesEntry.sourceDate()).isEqualTo("2026-01-01");
        assertThat(expensesEntry.isIllustrative()).isTrue();
        // B6 incident fix: laisvi pinigai = net_monthly_income - monthly_expenses = 3100 - 1350 =
        // 1750 (monthly_tax_setaside yra TIK rodymui - net_monthly_income JAU yra po mokesčių).
        assertThat(response.core().freeCashMonthly()).isEqualByComparingTo("1750.00");
    }

    @Test
    void monthlyExpensesProvidedByUserIsNotSurfacedAsAssumption() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("9999.00"), true));

        SavingsPlanResponse response = service.plan(
                request(yearsGoal("15000", 5), "3100", "900", "1350"), null);

        assertThat(response.core().assumptions()).noneMatch(e -> e.field().equals("monthly_expenses"));
        assertThat(response.core().freeCashMonthly()).isEqualByComparingTo("1750.00");
    }

    @Test
    void freeCashNeverSubtractsMonthlyTaxSetasideOnlyNetIncomeMinusExpenses() {
        // B6 live-eval incident (2026-11-13, docs/specs/taupymo-tikslai/B6-log.log), owner-supplied
        // real numbers from the actually-failing case: net_monthly_income=2215.87,
        // monthly_expenses=1400, monthly_tax_setaside=450.80. Correct laisvi pinigai = 2215.87 -
        // 1400 = 815.87. The pre-fix bug additionally subtracted monthly_tax_setaside, producing
        // 2215.87 - 450.80 - 1400 = 365.07 - the incident's own observed (wrong) output.
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(BigDecimal.ZERO, false));

        SavingsPlanResponse response = service.plan(
                request(yearsGoal("15000", 5), "2215.87", "450.80", "1400"), null);

        assertThat(response.core().freeCashMonthly()).isEqualByComparingTo("815.87");
    }

    @Test
    void omittedReturnAndInflationAssumptionsAreSurfacedWithAssumptionOrigin() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));

        SavingsPlanResponse response = service.plan(
                request(yearsGoal("15000", 5), "3100", "900", "1350"), null);

        List<AssumptionEntry> assumptions = response.core().assumptions();
        assertThat(assumptions).anyMatch(e -> e.field().equals("annual_return_pct") && e.origin() == Origin.ASSUMPTION);
        assertThat(assumptions).anyMatch(e -> e.field().equals("annual_inflation_pct") && e.origin() == Origin.ASSUMPTION);
    }

    @Test
    void userProvidedReturnAndInflationAreNotSurfacedAsAssumptions() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));
        GoalRequest goal = yearsGoal("15000", 5);
        SavingsPlanRequest req = new SavingsPlanRequest(goal, new BigDecimal("3100"), new BigDecimal("900"),
                new BigDecimal("1350"), null, new AssumptionsRequest(BigDecimal.ZERO, BigDecimal.ZERO));

        SavingsPlanResponse response = service.plan(req, null);

        assertThat(response.core().assumptions()).noneMatch(e -> e.field().equals("annual_return_pct"));
        assertThat(response.core().assumptions()).noneMatch(e -> e.field().equals("annual_inflation_pct"));
    }

    @Test
    void missingGoalIsRejectedWith400() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));
        SavingsPlanRequest req = request(null, "3100", "900", "1350");

        assertThatThrownBy(() -> service.plan(req, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("goal");
    }

    @Test
    void missingNetMonthlyIncomeIsRejectedWith400() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));
        SavingsPlanRequest req = request(yearsGoal("15000", 5), null, null, "1350");

        assertThatThrownBy(() -> service.plan(req, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("net_monthly_income");
    }

    @Test
    void netMonthlyIncomeSmallerThanTaxSetasideIsNoLongerAValidationErrorSinceTaxSetasideIsDisplayOnly() {
        // B6 incident fix removed the old cross-field check here: net_monthly_income (500) <
        // monthly_tax_setaside (900) used to 400 because the OLD buggy formula would have derived
        // a negative internal "income". monthly_tax_setaside is now display-only and never enters
        // the free-cash calculation, so this combination is just a (perfectly legitimate, if high
        // effective tax rate) NO_FREE_CASH result, not a validation error.
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));
        SavingsPlanRequest req = request(yearsGoal("15000", 5), "500", "900", "1350");

        SavingsPlanResponse response = service.plan(req, null);

        // laisvi pinigai = 500 - 1350 = -850 (monthly_tax_setaside=900 plays no part at all).
        assertThat(response.core().freeCashMonthly()).isEqualByComparingTo("-850.00");
        assertThat(response.core().fits()).isFalse();
    }

    @Test
    void dateModeWithoutTargetYearIsRejectedNamingRealRequestFieldNotInternalTargetYear() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));
        // GoalsService.validate() tikrina GoalInput.targetYear ir žinoma tik apie tą vidinį pavadinimą -
        // klaida turi įvardinti tikrąjį SavingsPlanRequest lauką goal.target_year, ne targetYear.
        GoalRequest goal = new GoalRequest(GoalType.HOME_DOWN_PAYMENT, GoalMode.DATE, new BigDecimal("15000"), null,
                null, null, null, null, null);
        SavingsPlanRequest req = request(goal, "3100", "900", "1350");

        assertThatThrownBy(() -> service.plan(req, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("goal.target_year")
                .hasMessageNotContaining("targetYear");
    }

    @Test
    void childAgeModeWithoutAgesIsRejectedNamingRealRequestFieldsNotInternalChildAgeNames() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));
        GoalRequest goal = new GoalRequest(GoalType.CHILD, GoalMode.CHILD_AGE, new BigDecimal("15000"), null, null,
                null, null, null, null);
        SavingsPlanRequest req = request(goal, "3100", "900", "1350");

        assertThatThrownBy(() -> service.plan(req, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("goal.child_current_age")
                .hasMessageContaining("goal.child_target_age")
                .hasMessageNotContaining("childCurrentAge")
                .hasMessageNotContaining("childTargetAge");
    }

    @Test
    void whenModeWithoutMonthlyContributionIsRejectedNamingRealRequestFieldNotInternalMonthlyContribution() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));
        GoalRequest goal = new GoalRequest(GoalType.CAR, GoalMode.WHEN, new BigDecimal("15000"), null, null, null,
                null, null, null);
        SavingsPlanRequest req = request(goal, "3100", "900", "1350");

        assertThatThrownBy(() -> service.plan(req, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("goal.monthly_contribution")
                .hasMessageNotContaining("monthlyContribution");
    }

    @Test
    void missingGoalTypeIsRejectedNamingRealRequestFieldNotInternalGoalType() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));
        GoalRequest goal = new GoalRequest(null, GoalMode.YEARS, new BigDecimal("15000"), 5, null, null, null, null,
                null);
        SavingsPlanRequest req = request(goal, "3100", "900", "1350");

        assertThatThrownBy(() -> service.plan(req, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("goal.type")
                .hasMessageNotContaining("goalType");
    }

    @Test
    void negativeAnnualReturnPctIsRejectedNamingRealRequestFieldNotInternalAnnualReturn() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));
        GoalRequest goal = yearsGoal("15000", 5);
        SavingsPlanRequest req = new SavingsPlanRequest(goal, new BigDecimal("3100"), new BigDecimal("900"),
                new BigDecimal("1350"), null, new AssumptionsRequest(new BigDecimal("-1"), null));

        assertThatThrownBy(() -> service.plan(req, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("assumptions.annual_return_pct")
                .hasMessageNotContaining("annualReturn");
    }

    /*
     * Toliau esantys 6 testai: ENGINE_FIELD_TO_PUBLIC_FIELD vertimas čia tikrinamas visiems likusiems
     * raktams, kurių translateEngineFieldNames() vertimas (review SHOULD, round 2) iki šiol NEBUVO
     * padengtas - mode/amount/years/targetMonth/annualInflation/expenses. Keturiems iš jų
     * (mode/amount/years/expenses) translated reikšmė (pvz. "goal.mode") PATI SAVYJE turi vidinio
     * lauko vardą kaip substring'ą ("mode" yra "goal.mode" dalis), todėl hasMessageNotContaining(vidinis
     * vardas) - stilius, naudotas aukščiau "annualReturn"/"targetYear" testuose - būtų klaidingai
     * teigiamas (false positive: testas "praeitų" net jei vertimas neįvyktų, nes substring vis tiek
     * atsirastų per prefiksą). Todėl šiems keturiems tikrinamas TIKSLUS getReason() (vienintelė klaida,
     * užklausa parinkta taip, kad kita validacija nesusijungtų su ";"), o likusiems dviem
     * (targetMonth/annualInflation, kur substring kolizijos nėra) - ta pati hasMessageContaining/
     * hasMessageNotContaining forma, kaip ir esamuose testuose.
     */

    @Test
    void missingModeIsRejectedNamingRealRequestFieldNotInternalMode() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));
        // GoalRequest.mode neturi savo bean validacijos - null pasiekia GoalsService.validate(), kuris
        // iškart grąžina "mode privalomas" (be kitų klaidų, nes be mode negalima tikrinti termino laukų).
        GoalRequest goal = new GoalRequest(GoalType.HOME_DOWN_PAYMENT, null, new BigDecimal("15000"), 5, null, null,
                null, null, null);
        SavingsPlanRequest req = request(goal, "3100", "900", "1350");

        ResponseStatusException ex = (ResponseStatusException) catchThrowable(() -> service.plan(req, null));

        assertThat(ex.getReason()).isEqualTo("goal.mode privalomas");
    }

    @Test
    void negativeGoalAmountIsRejectedNamingRealRequestFieldNotInternalAmount() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));
        // SavingsPlanService.validate() patikrina tik net_monthly_income/monthly_tax_setaside/
        // monthly_expenses - goal.amount pasiekia tik GoalsService.validate() "amount" tikrinimą.
        GoalRequest goal = new GoalRequest(GoalType.HOME_DOWN_PAYMENT, GoalMode.YEARS, new BigDecimal("-1"), 5, null,
                null, null, null, null);
        SavingsPlanRequest req = request(goal, "3100", "900", "1350");

        ResponseStatusException ex = (ResponseStatusException) catchThrowable(() -> service.plan(req, null));

        assertThat(ex.getReason()).isEqualTo("goal.amount negali būti neigiamas");
    }

    @Test
    void yearsOutOfRangeIsRejectedNamingRealRequestFieldNotInternalYears() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));
        GoalRequest goal = yearsGoal("15000", 0);
        SavingsPlanRequest req = request(goal, "3100", "900", "1350");

        ResponseStatusException ex = (ResponseStatusException) catchThrowable(() -> service.plan(req, null));

        assertThat(ex.getReason()).isEqualTo("goal.years turi būti 1–100");
    }

    @Test
    void targetMonthOutOfRangeIsRejectedNamingRealRequestFieldNotInternalTargetMonth() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));
        GoalRequest goal = new GoalRequest(GoalType.HOME_DOWN_PAYMENT, GoalMode.DATE, new BigDecimal("15000"), null,
                2030, 13, null, null, null);
        SavingsPlanRequest req = request(goal, "3100", "900", "1350");

        assertThatThrownBy(() -> service.plan(req, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("goal.target_month")
                .hasMessageNotContaining("targetMonth");
    }

    @Test
    void negativeAnnualInflationPctIsRejectedNamingRealRequestFieldNotInternalAnnualInflation() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));
        GoalRequest goal = yearsGoal("15000", 5);
        SavingsPlanRequest req = new SavingsPlanRequest(goal, new BigDecimal("3100"), new BigDecimal("900"),
                new BigDecimal("1350"), null, new AssumptionsRequest(null, new BigDecimal("-1")));

        assertThatThrownBy(() -> service.plan(req, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("assumptions.annual_inflation_pct")
                .hasMessageNotContaining("annualInflation");
    }

    @Test
    void statisticsResolvedExpensesOutOfBoundsIsRejectedNamingRealRequestFieldNotInternalExpenses() {
        // monthly_expenses NĖRA praleistas apribojimas: kai klientas jo nenurodo, reikšmė ateina iš
        // StatDefaultsService.resolve() (statistika), kuri NEIŠVENGIA SavingsPlanService.checkAmount()
        // patikrinimo (tas tikrina tik request.monthlyExpenses(), kuris čia null) - todėl bloga
        // statistikos reikšmė pasiekia GoalsService.validate()'o "expenses" tikrinimą realiai, ne
        // tik teoriškai (review finding - žr. SavingsPlanService javadoc).
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("-50.00"), true));
        SavingsPlanRequest req = request(yearsGoal("15000", 5), "3100", "900", null);

        ResponseStatusException ex = (ResponseStatusException) catchThrowable(() -> service.plan(req, null));

        assertThat(ex.getReason()).isEqualTo("monthly_expenses negali būti neigiamas");
    }

    @Test
    void comfortableShareFitsWithLowTip() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(BigDecimal.ZERO, false));
        // laisvi: 3100-0-1000=2100; tikslas 60*monthly<=15000 reiktų apskaičiuoti; naudosime WHEN su maža įmoka
        SavingsPlanResponse response = service.plan(
                request(whenGoal("1200", "100"), "3100", "0", "1000"), null);

        assertThat(response.core().fits()).isTrue();
        assertThat(response.core().generalTip()).isNotBlank();
    }

    @Test
    void noFreeCashMeansDoesNotFitAndShareIsZero() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(BigDecimal.ZERO, false));

        SavingsPlanResponse response = service.plan(
                request(yearsGoal("15000", 5), "1000", "0", "2000"), null);

        assertThat(response.core().fits()).isFalse();
        assertThat(response.core().shareOfFreeCashPct()).isEqualByComparingTo("0.00");
        assertThat(response.core().freeCashMonthly()).isEqualByComparingTo("-1000.00");
    }

    /*
     * A5: eu.atsidek.goals.plan.web.LockedFeaturesController (ir prieš jį taupymo-tikslai.html JS)
     * naudoja šį viešą metodą, kad gautų TĄ PAČIĄ locked_features reikšmę kaip ir /api/v1/savings/plan
     * (PLAN-taupymas-agentas.md A0 sprendimas #1), be pilno SavingsPlanRequest (goal/pajamos
     * nereikalingi tik tam, kad sužinotum, kurios funkcijos užrakintos).
     */

    @Test
    void lockedFeaturesForAnonymousReturnsAllGatedFeaturesInFixedOrder() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysGrants(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));

        assertThat(service.lockedFeatures(null)).containsExactlyElementsOf(GATED);
    }

    @Test
    void lockedFeaturesForLoggedInUserWithAllFeaturesIsEmpty() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysGrants(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));

        assertThat(service.lockedFeatures(USER_ID)).isEmpty();
    }

    @Test
    void lockedFeaturesForLoggedInUserWithoutAnyFeatureMatchesAnonymous() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));

        assertThat(service.lockedFeatures(USER_ID)).containsExactlyElementsOf(GATED);
    }

    @Test
    void lockedFeaturesMatchesWhatPlanReturnsForSameUser() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));

        SavingsPlanResponse response = service.plan(request(yearsGoal("15000", 5), "3100", "900", "1350"), USER_ID);

        assertThat(service.lockedFeatures(USER_ID)).containsExactlyElementsOf(response.lockedFeatures());
    }

    @Test
    void disclaimerIsTheFixedPlanSentence() {
        SavingsPlanService service = new SavingsPlanService(entitlementAlwaysDenies(),
                statsResolvingExpensesTo(new BigDecimal("1350.00"), true));

        SavingsPlanResponse response = service.plan(
                request(yearsGoal("15000", 5), "3100", "900", "1350"), null);

        assertThat(response.core().disclaimer())
                .isEqualTo("Skaičiavimai informaciniai, tai nėra finansinis ar investavimo patarimas.");
    }
}
