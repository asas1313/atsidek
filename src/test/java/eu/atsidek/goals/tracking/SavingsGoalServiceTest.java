package eu.atsidek.goals.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import eu.atsidek.goals.Assumptions;
import eu.atsidek.goals.GoalInput;
import eu.atsidek.goals.GoalMode;
import eu.atsidek.goals.GoalResult;
import eu.atsidek.goals.GoalType;
import eu.atsidek.goals.GoalsEngine;
import eu.atsidek.goals.Household;
import eu.atsidek.goals.tracking.SavingsGoalService.Progress;
import eu.atsidek.goals.tracking.SavingsGoalService.SaveResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pure (be Spring konteksto) {@link SavingsGoalService} atvejai: mockintas {@link SavingsGoalStore},
 * fiksuotas {@link Clock} - trijų šakų {@code save(userId, input, confirmed)} elgsena (PLAN.md §5/§6
 * task 9) ir progreso skaičiavimas, pakartotinai naudojant {@code GoalsEngine} matematiką.
 */
class SavingsGoalServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID GOAL_ID = UUID.randomUUID();

    private static Clock fixedClockAt(String isoInstant) {
        return Clock.fixed(Instant.parse(isoInstant), ZoneOffset.UTC);
    }

    private static GoalInput sampleInput() {
        return new GoalInput(GoalType.CAR, GoalMode.YEARS, new BigDecimal("1200"), 1, null, null, null, null, null,
                new Household(null, null, null, null, null),
                new Assumptions(new BigDecimal("0"), new BigDecimal("0")), null);
    }

    private static SavingsGoal savedGoalWithInput(GoalInput input, Instant createdAt) {
        return new SavingsGoal(GOAL_ID, USER_ID, input.goalType(), input.amount(), LocalDate.now(), input, createdAt);
    }

    @Test
    void firstSaveInsertsUnconditionallyEvenWhenNotConfirmed() {
        SavingsGoalStore store = mock(SavingsGoalStore.class);
        when(store.findByUserId(USER_ID)).thenReturn(Optional.empty());
        GoalInput input = sampleInput();
        SavingsGoal inserted = savedGoalWithInput(input, Instant.parse("2026-01-01T00:00:00Z"));
        when(store.upsert(eq(USER_ID), eq(GoalType.CAR), eq(new BigDecimal("1200")), any(), eq(input)))
                .thenReturn(inserted);

        SavingsGoalService service = new SavingsGoalService(store, fixedClockAt("2026-01-01T00:00:00Z"));
        SaveResult result = service.save(USER_ID, input, false);

        assertThat(result.needsConfirmation()).isFalse();
        assertThat(result.goal()).isEqualTo(inserted);
        verify(store, never()).deleteContributions(any());
    }

    @Test
    void replaceWithoutConfirmReturnsNeedsConfirmationAndTouchesNothing() {
        SavingsGoalStore store = mock(SavingsGoalStore.class);
        GoalInput existingInput = sampleInput();
        SavingsGoal existing = savedGoalWithInput(existingInput, Instant.parse("2026-01-01T00:00:00Z"));
        when(store.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        when(store.countContributions(GOAL_ID)).thenReturn(3);

        SavingsGoalService service = new SavingsGoalService(store, fixedClockAt("2026-02-01T00:00:00Z"));
        GoalInput newInput = sampleInput();
        SaveResult result = service.save(USER_ID, newInput, false);

        assertThat(result.needsConfirmation()).isTrue();
        assertThat(result.existingContributionCount()).isEqualTo(3);
        verify(store, never()).upsert(any(), any(), any(), any(), any());
        verify(store, never()).deleteContributions(any());
    }

    @Test
    void replaceWithConfirmDeletesContributionsThenUpdatesGoal() {
        SavingsGoalStore store = mock(SavingsGoalStore.class);
        GoalInput existingInput = sampleInput();
        SavingsGoal existing = savedGoalWithInput(existingInput, Instant.parse("2026-01-01T00:00:00Z"));
        when(store.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
        GoalInput newInput = sampleInput();
        SavingsGoal replaced = savedGoalWithInput(newInput, Instant.parse("2026-02-01T00:00:00Z"));
        when(store.upsert(eq(USER_ID), any(), any(), any(), eq(newInput))).thenReturn(replaced);

        SavingsGoalService service = new SavingsGoalService(store, fixedClockAt("2026-02-01T00:00:00Z"));
        SaveResult result = service.save(USER_ID, newInput, true);

        assertThat(result.needsConfirmation()).isFalse();
        assertThat(result.goal()).isEqualTo(replaced);
        verify(store).deleteContributions(GOAL_ID);
        verify(store).upsert(eq(USER_ID), any(), any(), any(), eq(newInput));
    }

    @Test
    void invalidInputIsRejectedBeforeTouchingTheStore() {
        SavingsGoalStore store = mock(SavingsGoalStore.class);
        GoalInput invalid = new GoalInput(null, null, null, null, null, null, null, null, null, null, null, null);

        SavingsGoalService service = new SavingsGoalService(store, fixedClockAt("2026-01-01T00:00:00Z"));

        assertThatThrownBy(() -> service.save(USER_ID, invalid, false))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(store);
    }

    @Test
    void recordContributionRejectsNegativeAmount() {
        SavingsGoalStore store = mock(SavingsGoalStore.class);
        SavingsGoal goal = savedGoalWithInput(sampleInput(), Instant.parse("2026-01-01T00:00:00Z"));
        SavingsGoalService service = new SavingsGoalService(store, fixedClockAt("2026-01-01T00:00:00Z"));

        assertThatThrownBy(() -> service.recordContribution(goal, java.time.YearMonth.of(2026, 1), new BigDecimal("-5")))
                .isInstanceOf(ResponseStatusException.class);
        verify(store, never()).upsertContribution(any(), any(), any());
    }

    @Test
    void recordContributionRejectsAmountAboveMax() {
        SavingsGoalStore store = mock(SavingsGoalStore.class);
        SavingsGoal goal = savedGoalWithInput(sampleInput(), Instant.parse("2026-01-01T00:00:00Z"));
        SavingsGoalService service = new SavingsGoalService(store, fixedClockAt("2026-01-01T00:00:00Z"));

        assertThatThrownBy(() -> service.recordContribution(goal, java.time.YearMonth.of(2026, 1),
                new BigDecimal("100000001")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void recordContributionStoresValidAmount() {
        SavingsGoalStore store = mock(SavingsGoalStore.class);
        SavingsGoal goal = savedGoalWithInput(sampleInput(), Instant.parse("2026-01-01T00:00:00Z"));
        SavingsGoalService service = new SavingsGoalService(store, fixedClockAt("2026-01-01T00:00:00Z"));

        service.recordContribution(goal, java.time.YearMonth.of(2026, 1), new BigDecimal("100"));

        verify(store).upsertContribution(GOAL_ID, LocalDate.of(2026, 1, 1), new BigDecimal("100"));
    }

    @Test
    void progressComputesExpectedVsActualAtElapsedMonths() {
        SavingsGoalStore store = mock(SavingsGoalStore.class);
        SavingsGoal goal = savedGoalWithInput(sampleInput(), Instant.parse("2026-01-01T00:00:00Z"));
        when(store.findContributions(GOAL_ID)).thenReturn(List.of(
                new SavingsContribution(1, GOAL_ID, LocalDate.of(2026, 1, 1), new BigDecimal("100")),
                new SavingsContribution(2, GOAL_ID, LocalDate.of(2026, 2, 1), new BigDecimal("150"))));

        // 3 mėnesiai nuo createdAt (2026-01-01 -> 2026-04-01); amount=1200, years=1, rm=0 (be grąžos)
        // => monthly = 1200/12 = 100.00, numatyta po 3 mėn. = 300.00.
        SavingsGoalService service = new SavingsGoalService(store, fixedClockAt("2026-04-01T00:00:00Z"));
        Progress progress = service.progress(goal);

        assertThat(progress.totalMonths()).isEqualTo(12);
        assertThat(progress.elapsedMonths()).isEqualTo(3);
        assertThat(progress.expectedAccumulated()).isEqualByComparingTo("300.00");
        assertThat(progress.actualAccumulated()).isEqualByComparingTo("250");
        assertThat(progress.onTrack()).isFalse();
    }

    @Test
    void progressIsOnTrackWhenActualMeetsOrExceedsExpected() {
        SavingsGoalStore store = mock(SavingsGoalStore.class);
        SavingsGoal goal = savedGoalWithInput(sampleInput(), Instant.parse("2026-01-01T00:00:00Z"));
        when(store.findContributions(GOAL_ID)).thenReturn(List.of(
                new SavingsContribution(1, GOAL_ID, LocalDate.of(2026, 1, 1), new BigDecimal("120")),
                new SavingsContribution(2, GOAL_ID, LocalDate.of(2026, 2, 1), new BigDecimal("120")),
                new SavingsContribution(3, GOAL_ID, LocalDate.of(2026, 3, 1), new BigDecimal("120"))));

        SavingsGoalService service = new SavingsGoalService(store, fixedClockAt("2026-04-01T00:00:00Z"));
        Progress progress = service.progress(goal);

        assertThat(progress.actualAccumulated()).isEqualByComparingTo("360");
        assertThat(progress.onTrack()).isTrue();
    }

    @Test
    void progressClampsElapsedMonthsAtTotalMonths() {
        SavingsGoalStore store = mock(SavingsGoalStore.class);
        SavingsGoal goal = savedGoalWithInput(sampleInput(), Instant.parse("2026-01-01T00:00:00Z"));
        when(store.findContributions(GOAL_ID)).thenReturn(List.of());

        // 15 mėnesių praėjo, bet planas - 12 mėn.; neturi "pereiti" numatytos sumos už tikslo terminą.
        SavingsGoalService service = new SavingsGoalService(store, fixedClockAt("2027-04-01T00:00:00Z"));
        Progress progress = service.progress(goal);

        assertThat(progress.elapsedMonths()).isEqualTo(12);
        assertThat(progress.expectedAccumulated()).isEqualByComparingTo("1200.00");
    }

    @Test
    void progressResolvesNullAnnualReturnFromTheCurrentGoalsEngineDefaultEachCall() {
        // A0 sprendimas #2 / DB poveikio patikra: assumptions.annualReturn DB saugomas kaip null, o jo
        // skaitinė reikšmė NĖRA „užšaldyta" tikslo išsaugojimo metu - progress() ją kaskart iš naujo
        // ima iš GoalsEngine.DEFAULT_ANNUAL_RETURN. Todėl pakeitus numatytąją grąžą (3% -> 0%), jau
        // išsaugotų tikslų su null grąža progresas pasikeičia be jokios duomenų migracijos ar backfill'o.
        SavingsGoalStore store = mock(SavingsGoalStore.class);
        GoalInput input = new GoalInput(GoalType.CAR, GoalMode.YEARS, new BigDecimal("1200"), 1, null, null, null,
                null, null, new Household(null, null, null, null, null), new Assumptions(null, null), null);
        SavingsGoal goal = savedGoalWithInput(input, Instant.parse("2026-01-01T00:00:00Z"));
        when(store.findContributions(GOAL_ID)).thenReturn(List.of());

        SavingsGoalService service = new SavingsGoalService(store, fixedClockAt("2026-04-01T00:00:00Z"));
        Progress progress = service.progress(goal);

        GoalResult engineResult = new GoalsEngine().calculate(input, LocalDate.of(2026, 1, 1));
        BigDecimal rm = GoalsEngine.DEFAULT_ANNUAL_RETURN.divide(BigDecimal.valueOf(1200), 20, RoundingMode.HALF_UP);
        BigDecimal expected = GoalsEngine.balance(engineResult.monthly(), rm, progress.elapsedMonths())
                .setScale(2, RoundingMode.HALF_UP);

        assertThat(progress.expectedAccumulated()).isEqualByComparingTo(expected);

        // Papildomas, NE tautologinis patikrinimas: ties rm=0 sukaupta suma po k mėnesių yra tiesiog
        // monthly*k (be jokio sudėtinių palūkanų augimo) - tai formulė GoalsEngine.balance() naudoja
        // TIK kai rm.signum()==0. Jei numatytoji grąža būtų grąžinta į 3 % (rm!=0), balance() naudotų
        // sudėtinių palūkanų formulę ir rezultatas skirtųsi nuo šio plokščio sandaugos - testas sulūžtų.
        BigDecimal flatMultiple = engineResult.monthly().multiply(BigDecimal.valueOf(progress.elapsedMonths()));
        assertThat(progress.expectedAccumulated()).isEqualByComparingTo(flatMultiple);
    }
}
