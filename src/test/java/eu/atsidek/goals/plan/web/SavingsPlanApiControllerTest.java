package eu.atsidek.goals.plan.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code POST /api/v1/savings/plan} (PLAN-taupymas-agentas.md A3/A4). Reikalingas realus PostgreSQL
 * (Testcontainers) - {@code StatDefaultsService}/{@code EntitlementService} skaito iš DB; šio
 * sandbox'o aprašyme ŽINOMA, kad šis testas šičia NEĮVYKDOMAS (nėra Docker) - žr. agent'o ataskaitą.
 * Teisėms naudojamos TIKROS {@code entitlement} eilutės konkrečiam naudotojui (ne globalus beta
 * flag'as), kad testai nepriklausytų nuo {@code EntitlementService} ~30s talpyklos lango tarp testų
 * (žr. {@code SavingsGoalControllerTest} tą pačią pastabą).
 */
@SpringBootTest(properties = "atsidek.api.key-hashes=62af8704764faf8ea82fc61ce9c4c3908b6cb97d463a634e9e587d7c885db0ef")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SavingsPlanApiControllerTest {

    private static final String API_KEY = "test-key";

    @Autowired
    MockMvc mvc;
    @Autowired
    AppUserStore appUserStore;
    @Autowired
    JdbcTemplate jdbc;

    private ResultActions plan(String json) throws Exception {
        return mvc.perform(post("/api/v1/savings/plan").header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions planAs(AppOidcUser user, String json) throws Exception {
        return mvc.perform(post("/api/v1/savings/plan").header("X-API-Key", API_KEY).with(oidcLogin().oidcUser(user))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

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

    private static final String YEARS_REQUEST = """
            {"goal":{"type":"HOME_DOWN_PAYMENT","amount":15000,"mode":"YEARS","years":5},
             "net_monthly_income":3100,"monthly_tax_setaside":900,"monthly_expenses":1350}
            """;

    private static final String WHEN_REQUEST = """
            {"goal":{"type":"CAR","amount":1200,"mode":"WHEN","monthly_contribution":100},
             "net_monthly_income":3100,"monthly_expenses":1000}
            """;

    @Test
    void rejectsMissingOrWrongApiKey() throws Exception {
        mvc.perform(post("/api/v1/savings/plan").contentType(MediaType.APPLICATION_JSON).content(YEARS_REQUEST))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/savings/plan").header("X-API-Key", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(YEARS_REQUEST))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousYearsModeHappyPathHasNoPremiumKeyAndAllFeaturesLocked() throws Exception {
        plan(YEARS_REQUEST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("FREE"))
                .andExpect(jsonPath("$.core.monthly_contribution").exists())
                .andExpect(jsonPath("$.core.free_cash_monthly").value(850.00))
                .andExpect(jsonPath("$.core.disclaimer")
                        .value("Skaičiavimai informaciniai, tai nėra finansinis ar investavimo patarimas."))
                .andExpect(jsonPath("$.locked_features", org.hamcrest.Matchers.containsInAnyOrder(
                        "SAVINGS_MULTI_GOAL", "SAVINGS_IV_LINK", "SAVINGS_SCENARIOS")))
                .andExpect(jsonPath("$.premium").doesNotExist());
    }

    @Test
    void anonymousWhenModeHappyPath() throws Exception {
        plan(WHEN_REQUEST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.core.monthly_contribution").value(100.00))
                .andExpect(jsonPath("$.premium").doesNotExist());
    }

    @Test
    void loggedInUserWithAllGatedFeaturesGetsPremiumTierAndPremiumKey() throws Exception {
        AppOidcUser user = userWithFullAccess("full-access");

        planAs(user, YEARS_REQUEST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("PREMIUM"))
                .andExpect(jsonPath("$.locked_features").isArray())
                .andExpect(jsonPath("$.locked_features", org.hamcrest.Matchers.empty()))
                .andExpect(jsonPath("$.premium").exists());
    }

    @Test
    void loggedInUserWithoutGatedFeaturesStillGetsPremiumKeyButFreeTier() throws Exception {
        AppOidcUser user = userWithoutAccess("no-access");

        planAs(user, YEARS_REQUEST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("FREE"))
                .andExpect(jsonPath("$.locked_features", org.hamcrest.Matchers.containsInAnyOrder(
                        "SAVINGS_MULTI_GOAL", "SAVINGS_IV_LINK", "SAVINGS_SCENARIOS")))
                .andExpect(jsonPath("$.premium").exists());
    }

    @Test
    void missingGoalIsRejectedWith400() throws Exception {
        plan("{\"net_monthly_income\":3100}").andExpect(status().isBadRequest());
    }

    @Test
    void missingNetMonthlyIncomeIsRejectedWith400() throws Exception {
        plan("{\"goal\":{\"type\":\"CAR\",\"amount\":1200,\"mode\":\"YEARS\",\"years\":5}}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownFieldIsRejectedWith400() throws Exception {
        plan("""
                {"goal":{"type":"CAR","amount":1200,"mode":"YEARS","years":5},
                 "net_monthly_income":3100,"unexpected_field":true}
                """).andExpect(status().isBadRequest());
    }

    @Test
    void monthlyExpensesOmittedIsResolvedFromStatisticsAndMarkedWithSource() throws Exception {
        plan("""
                {"goal":{"type":"HOME_DOWN_PAYMENT","amount":15000,"mode":"YEARS","years":5},
                 "net_monthly_income":3100,"monthly_tax_setaside":900}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.core.assumptions[?(@.field=='monthly_expenses')].origin")
                        .value(org.hamcrest.Matchers.contains("STATISTICS")))
                .andExpect(jsonPath("$.core.assumptions[?(@.field=='monthly_expenses')].source").exists())
                .andExpect(jsonPath("$.core.assumptions[?(@.field=='monthly_expenses')].is_illustrative")
                        .value(org.hamcrest.Matchers.contains(true)));
    }
}
