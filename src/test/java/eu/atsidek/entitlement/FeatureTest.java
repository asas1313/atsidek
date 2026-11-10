package eu.atsidek.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Užrakina tikslią {@link Feature} reikšmių aibę (žr. plano §6 task 2) - jos naujinamos sąmoningai,
 * ne atsitiktinai ištrynus/pridėjus enum konstantą.
 */
class FeatureTest {

    @Test
    void containsExactlyTheApprovedFeatureSet() {
        assertThat(Feature.values()).extracting(Enum::name).containsExactlyInAnyOrder(
                "SAVINGS_TRACKING", "SAVINGS_MULTI_GOAL", "SAVINGS_SCENARIOS",
                "SAVINGS_IV_LINK", "AI_PLAN_CHAT", "PDF_EXPORT");
    }
}
