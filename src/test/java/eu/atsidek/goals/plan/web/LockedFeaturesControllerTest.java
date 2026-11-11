package eu.atsidek.goals.plan.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.atsidek.TestcontainersConfiguration;
import eu.atsidek.auth.AppOidcUser;
import eu.atsidek.entitlement.AppUser;
import eu.atsidek.entitlement.AppUserStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /goals/savings/locked-features} (A5, PLAN-taupymas-agentas.md) - viešas, be API rakto,
 * be prisijungimo (SecurityConfig permitAll), kaip ir {@code /goals/stats/defaults}
 * ({@code StatDefaultsControllerTest}). Reikalingas realus PostgreSQL (Testcontainers) -
 * {@code EntitlementService} skaito iš DB; šio sandbox'o aprašyme ŽINOMA, kad šis testas čia
 * NEĮVYKDOMAS (nėra Docker) - žr. agento ataskaitą. Teisėms naudojamos TIKROS {@code entitlement}
 * eilutės konkrečiam naudotojui (ne globalus beta flag'as), kad testai nepriklausytų nuo
 * {@code EntitlementService} ~30s talpyklos lango tarp testų (žr. {@code SavingsPlanApiControllerTest}
 * tą pačią pastabą).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LockedFeaturesControllerTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    AppUserStore appUserStore;
    @Autowired
    JdbcTemplate jdbc;

    private AppOidcUser userWithFullAccess(String label) {
        AppUser appUser = appUserStore.upsert(UUID.randomUUID(), "google", "subject-" + label + "-" + UUID.randomUUID(),
                label + "@example.com");
        for (String feature : List.of("SAVINGS_MULTI_GOAL", "SAVINGS_IV_LINK", "SAVINGS_SCENARIOS")) {
            jdbc.update("INSERT INTO entitlement (user_id, feature, source, valid_from) VALUES (?, ?, 'BETA', now())",
                    appUser.id(), feature);
        }
        return appOidcUser(appUser.id(), appUser.providerSubject());
    }

    private AppOidcUser userWithoutAccess(String label) {
        AppUser appUser = appUserStore.upsert(UUID.randomUUID(), "google", "subject-" + label + "-" + UUID.randomUUID(),
                label + "@example.com");
        return appOidcUser(appUser.id(), appUser.providerSubject());
    }

    private static AppOidcUser appOidcUser(UUID appUserId, String subject) {
        OidcIdToken idToken = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("sub", subject, "email", subject + "@example.com", "email_verified", true));
        DefaultOidcUser delegate = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("SCOPE_openid")), idToken);
        return new AppOidcUser(delegate, appUserId, false);
    }

    @Test
    void anonymousGetsAllThreeGatedFeaturesLocked() throws Exception {
        mvc.perform(get("/goals/savings/locked-features"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockedFeatures", Matchers.containsInAnyOrder(
                        "SAVINGS_MULTI_GOAL", "SAVINGS_IV_LINK", "SAVINGS_SCENARIOS")));
    }

    @Test
    void loggedInUserWithAllGatedFeaturesGetsEmptyList() throws Exception {
        AppOidcUser user = userWithFullAccess("full-access");

        mvc.perform(get("/goals/savings/locked-features").with(oidcLogin().oidcUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockedFeatures").isArray())
                .andExpect(jsonPath("$.lockedFeatures", Matchers.empty()));
    }

    @Test
    void loggedInUserWithoutGatedFeaturesStillSeesAllThreeLocked() throws Exception {
        AppOidcUser user = userWithoutAccess("no-access");

        mvc.perform(get("/goals/savings/locked-features").with(oidcLogin().oidcUser(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockedFeatures", Matchers.containsInAnyOrder(
                        "SAVINGS_MULTI_GOAL", "SAVINGS_IV_LINK", "SAVINGS_SCENARIOS")));
    }
}
