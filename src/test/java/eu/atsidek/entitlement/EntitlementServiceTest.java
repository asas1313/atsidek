package eu.atsidek.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure (be Spring konteksto) {@link EntitlementService} atvejai: fiksuotas {@link Clock}, mockintas
 * {@link EntitlementStore} - žr. plano §6 task 5.
 */
class EntitlementServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Test
    void betaOffNoRowsGrantsNothing() {
        EntitlementStore store = mock(EntitlementStore.class);
        when(store.findSettingValue("goals_beta_mode_enabled")).thenReturn(Optional.empty());
        when(store.findByUserAndFeature(USER_ID, Feature.SAVINGS_TRACKING)).thenReturn(List.of());

        EntitlementService service = new EntitlementService(store, fixedClock());

        assertThat(service.hasFeature(USER_ID, Feature.SAVINGS_TRACKING)).isFalse();
    }

    @Test
    void betaOnGrantsAllMatrixFeatures() {
        EntitlementStore store = mock(EntitlementStore.class);
        when(store.findSettingValue("goals_beta_mode_enabled")).thenReturn(Optional.of("true"));

        EntitlementService service = new EntitlementService(store, fixedClock());

        for (Feature feature : Feature.values()) {
            assertThat(service.hasFeature(USER_ID, feature)).as(feature.name()).isTrue();
        }
        // Betai įjungtai, nereikia skaityti pavienių entitlement eilučių.
        verify(store, never()).findByUserAndFeature(any(), any());
    }

    @Test
    void expiredRowNotHonoured() {
        EntitlementStore store = mock(EntitlementStore.class);
        when(store.findSettingValue("goals_beta_mode_enabled")).thenReturn(Optional.empty());
        Entitlement expired = new Entitlement(1L, USER_ID, Feature.SAVINGS_TRACKING, "BETA",
                NOW.minusSeconds(864000), NOW.minusSeconds(86400), NOW.minusSeconds(864000));
        when(store.findByUserAndFeature(USER_ID, Feature.SAVINGS_TRACKING)).thenReturn(List.of(expired));

        EntitlementService service = new EntitlementService(store, fixedClock());

        assertThat(service.hasFeature(USER_ID, Feature.SAVINGS_TRACKING)).isFalse();
    }

    @Test
    void notYetValidRowNotHonoured() {
        EntitlementStore store = mock(EntitlementStore.class);
        when(store.findSettingValue("goals_beta_mode_enabled")).thenReturn(Optional.empty());
        Entitlement future = new Entitlement(2L, USER_ID, Feature.SAVINGS_TRACKING, "BETA",
                NOW.plusSeconds(86400), null, NOW);
        when(store.findByUserAndFeature(USER_ID, Feature.SAVINGS_TRACKING)).thenReturn(List.of(future));

        EntitlementService service = new EntitlementService(store, fixedClock());

        assertThat(service.hasFeature(USER_ID, Feature.SAVINGS_TRACKING)).isFalse();
    }

    @Test
    void validRowWithinWindowGrantsFeature() {
        EntitlementStore store = mock(EntitlementStore.class);
        when(store.findSettingValue("goals_beta_mode_enabled")).thenReturn(Optional.empty());
        Entitlement valid = new Entitlement(3L, USER_ID, Feature.SAVINGS_TRACKING, "BETA",
                NOW.minusSeconds(3600), NOW.plusSeconds(3600), NOW.minusSeconds(3600));
        when(store.findByUserAndFeature(USER_ID, Feature.SAVINGS_TRACKING)).thenReturn(List.of(valid));

        EntitlementService service = new EntitlementService(store, fixedClock());

        assertThat(service.hasFeature(USER_ID, Feature.SAVINGS_TRACKING)).isTrue();
    }

    @Test
    void validRowWithNoValidUntilGrantsFeature() {
        EntitlementStore store = mock(EntitlementStore.class);
        when(store.findSettingValue("goals_beta_mode_enabled")).thenReturn(Optional.empty());
        Entitlement valid = new Entitlement(4L, USER_ID, Feature.SAVINGS_TRACKING, "BETA",
                NOW.minusSeconds(3600), null, NOW.minusSeconds(3600));
        when(store.findByUserAndFeature(eq(USER_ID), eq(Feature.SAVINGS_TRACKING))).thenReturn(List.of(valid));

        EntitlementService service = new EntitlementService(store, fixedClock());

        assertThat(service.hasFeature(USER_ID, Feature.SAVINGS_TRACKING)).isTrue();
    }

    @Test
    void doesNotLookUpEntitlementRowsForAnotherUser() {
        EntitlementStore store = mock(EntitlementStore.class);
        when(store.findSettingValue("goals_beta_mode_enabled")).thenReturn(Optional.empty());
        when(store.findByUserAndFeature(any(), any())).thenReturn(List.of());

        EntitlementService service = new EntitlementService(store, fixedClock());
        service.hasFeature(USER_ID, Feature.SAVINGS_TRACKING);

        org.mockito.Mockito.verify(store).findByUserAndFeature(USER_ID, Feature.SAVINGS_TRACKING);
    }
}
