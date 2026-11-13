package eu.atsidek.evalci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.atsidek.TestcontainersConfiguration;
import eu.atsidek.auth.ContextTokenService;
import eu.atsidek.entitlement.EntitlementStore;
import eu.atsidek.entitlement.Feature;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link EvalTokenService} - B6 gyvo eval'o CI testinio FREE/PREMIUM vartotojo žetono mintimas.
 * {@code @ActiveProfiles("eval-ci")} čia TYČIA - be jo šis bean'as (žr. {@code @Profile} klasės
 * deklaracijoje) net neegzistuotų Spring kontekste, žr. {@link EvalTokenServiceProfileGatingTest}
 * dėl to priešingo (neigiamo) atvejo.
 */
@SpringBootTest(properties = "atsidek.chat.context-token-secret=test-context-token-secret")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("eval-ci")
class EvalTokenServiceTest {

    private static final String TOKEN_SECRET = "test-context-token-secret";

    @Autowired
    EvalTokenService service;

    @Autowired
    EntitlementStore entitlementStore;

    @Autowired
    Clock clock;

    @Test
    void premiumKeyMintsATokenWithPremiumTierAndAllFeaturesGranted() {
        EvalTokenService.Minted minted = service.mint(EvalTokenService.PREMIUM_KEY);

        assertThat(minted.derivedTier()).isEqualTo("PREMIUM");
        for (Feature feature : Feature.values()) {
            assertThat(entitlementStore.findByUserAndFeature(minted.userId(), feature))
                    .as(feature.name()).isNotEmpty();
        }

        var claims = new ContextTokenService(TOKEN_SECRET, clock).verify(minted.contextToken()).orElseThrow();
        assertThat(claims.userId()).contains(minted.userId());
        assertThat(claims.tier()).isEqualTo("PREMIUM");
    }

    @Test
    void freeKeyMintsATokenWithFreeTierAndNoFeaturesGranted() {
        EvalTokenService.Minted minted = service.mint(EvalTokenService.FREE_KEY);

        assertThat(minted.derivedTier()).isEqualTo("FREE");
        for (Feature feature : Feature.values()) {
            assertThat(entitlementStore.findByUserAndFeature(minted.userId(), feature))
                    .as(feature.name()).isEmpty();
        }

        var claims = new ContextTokenService(TOKEN_SECRET, clock).verify(minted.contextToken()).orElseThrow();
        assertThat(claims.userId()).contains(minted.userId());
        assertThat(claims.tier()).isEqualTo("FREE");
    }

    @Test
    void repeatedCallsForTheSameKeyReturnTheSameUserIdAndDoNotDuplicateEntitlements() {
        EvalTokenService.Minted first = service.mint(EvalTokenService.PREMIUM_KEY);
        EvalTokenService.Minted second = service.mint(EvalTokenService.PREMIUM_KEY);

        assertThat(second.userId()).isEqualTo(first.userId());
        assertThat(entitlementStore.findByUserAndFeature(first.userId(), Feature.SAVINGS_MULTI_GOAL)).hasSize(1);
    }

    @Test
    void freeAndPremiumKeysMapToDifferentStableUsers() {
        EvalTokenService.Minted free = service.mint(EvalTokenService.FREE_KEY);
        EvalTokenService.Minted premium = service.mint(EvalTokenService.PREMIUM_KEY);

        assertThat(free.userId()).isNotEqualTo(premium.userId());
    }

    @Test
    void unknownTestUserKeyIsRejectedWith400() {
        assertThatThrownBy(() -> service.mint("admin"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void blankTestUserKeyIsRejectedWith400() {
        assertThatThrownBy(() -> service.mint(""))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.mint(null))
                .isInstanceOf(ResponseStatusException.class);
    }
}
