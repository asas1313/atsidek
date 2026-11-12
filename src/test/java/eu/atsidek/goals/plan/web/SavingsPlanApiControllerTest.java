package eu.atsidek.goals.plan.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.atsidek.TestcontainersConfiguration;
import eu.atsidek.auth.AppOidcUser;
import eu.atsidek.auth.ContextTokenService;
import eu.atsidek.entitlement.AppUser;
import eu.atsidek.entitlement.AppUserStore;
import java.time.Clock;
import java.time.Duration;
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
 * (Testcontainers) - {@code StatDefaultsService}/{@code EntitlementService} skaito iš DB.
 * Teisėms naudojamos TIKROS {@code entitlement} eilutės konkrečiam naudotojui (ne globalus beta
 * flag'as), kad testai nepriklausytų nuo {@code EntitlementService} ~30s talpyklos lango tarp testų
 * (žr. {@code SavingsGoalControllerTest} tą pačią pastabą).
 */
@SpringBootTest(properties = {
        "atsidek.api.key-hashes=62af8704764faf8ea82fc61ce9c4c3908b6cb97d463a634e9e587d7c885db0ef",
        "atsidek.chat.context-token-secret=test-context-token-secret"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SavingsPlanApiControllerTest {

    private static final String API_KEY = "test-key";
    private static final String TOKEN_SECRET = "test-context-token-secret";

    @Autowired
    MockMvc mvc;
    @Autowired
    AppUserStore appUserStore;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Clock clock;

    private ResultActions plan(String json) throws Exception {
        return mvc.perform(post("/api/v1/savings/plan").header("X-API-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions planAs(AppOidcUser user, String json) throws Exception {
        return mvc.perform(post("/api/v1/savings/plan").header("X-API-Key", API_KEY).with(oidcLogin().oidcUser(user))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /**
     * SĄMONINGAI BE {@code oidcLogin()} - įrodo, kad tapatybę atnešė žetonas (ADR-0003), ne
     * naršyklės sesija, kurios čia nėra (tiksliai kaip ir realiame Python→Java kvietime).
     */
    private ResultActions planWithToken(String token, String json) throws Exception {
        return mvc.perform(post("/api/v1/savings/plan").header("X-API-Key", API_KEY)
                .header("X-Atsidek-Context-Token", token)
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
    void missingContextTokenBehavesExactlyAsBeforeAnonymousSessionOnly() throws Exception {
        // Esamas elgesys (be žetono, be sesijos) nepakinta - šis testas yra tiesioginis "zero
        // behaviour change" regresijos įrodymas (lygiai tas pats atvejis kaip anonymousYearsModeHappyPath).
        plan(YEARS_REQUEST).andExpect(status().isOk()).andExpect(jsonPath("$.tier").value("FREE"));
    }

    @Test
    void validContextTokenSuppliesIdentityEvenWithoutABrowserSession() throws Exception {
        AppOidcUser user = userWithFullAccess("token-full-access");
        String token = new ContextTokenService(TOKEN_SECRET, clock).issue(user.appUserId(), "PREMIUM");

        planWithToken(token, YEARS_REQUEST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("PREMIUM"))
                .andExpect(jsonPath("$.locked_features", org.hamcrest.Matchers.empty()));
    }

    @Test
    void contextTokenPremiumClaimIsIgnoredTierIsAlwaysReDerivedFromEntitlementService() throws Exception {
        // Vartotojas TURI appUserId (žetonas teisėtas), bet NETURI JOKIŲ entitlement eilučių -
        // žetonas melagingai teigia "PREMIUM", bet atsakymas privalo rodyti FREE su užrakintomis
        // funkcijomis, nes tier'as visada perskaičiuojamas iš EntitlementService, o ne skaitomas iš
        // žetono (žetonas - tik tapatybės relė, ne teisių šaltinis, žr. ADR-0003).
        AppOidcUser user = userWithoutAccess("token-no-access");
        String token = new ContextTokenService(TOKEN_SECRET, clock).issue(user.appUserId(), "PREMIUM");

        planWithToken(token, YEARS_REQUEST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("FREE"))
                .andExpect(jsonPath("$.locked_features", org.hamcrest.Matchers.containsInAnyOrder(
                        "SAVINGS_MULTI_GOAL", "SAVINGS_IV_LINK", "SAVINGS_SCENARIOS")));
    }

    @Test
    void forgedContextTokenIsRejected() throws Exception {
        String genuine = new ContextTokenService(TOKEN_SECRET, clock).issue(UUID.randomUUID(), "PREMIUM");
        String forged = forgeSignature(genuine);

        planWithToken(forged, YEARS_REQUEST).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredContextTokenIsRejected() throws Exception {
        Clock past = Clock.fixed(clock.instant().minus(Duration.ofMinutes(10)), clock.getZone());
        String expired = new ContextTokenService(TOKEN_SECRET, past).issue(UUID.randomUUID(), "PREMIUM");

        planWithToken(expired, YEARS_REQUEST).andExpect(status().isUnauthorized());
    }

    /**
     * Pakeičia PIRMĄ parašo dalies simbolį (ne paskutinį - jis gali kirpti nepilną base64 grupę,
     * kur kelios reikšmės iškoduojamos į tą patį baitą) - tai GARANTUOTAI pakeičia dekoduotą
     * parašą, kadangi jis yra pilnos 4 simbolių/3 baitų grupės dalis (žr. tą pačią pastabą
     * {@code TaxApiControllerTest}).
     */
    private static String forgeSignature(String token) {
        int dot = token.indexOf('.');
        char first = token.charAt(dot + 1);
        char replacement = first == 'A' ? 'Z' : 'A';
        return token.substring(0, dot + 1) + replacement + token.substring(dot + 2);
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
    void nonJsonContentTypeIsRejectedWith415() throws Exception {
        mvc.perform(post("/api/v1/savings/plan").header("X-API-Key", API_KEY)
                        .contentType(MediaType.TEXT_PLAIN).content(YEARS_REQUEST))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void missingContentTypeIsRejectedWith415() throws Exception {
        mvc.perform(post("/api/v1/savings/plan").header("X-API-Key", API_KEY).content(YEARS_REQUEST))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void trailingGarbageAfterValidJsonIsRejectedWith400() throws Exception {
        mvc.perform(post("/api/v1/savings/plan").header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"goal":{"type":"CAR","amount":1200,"mode":"YEARS","years":5},
                                 "net_monthly_income":3100} garbage
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativeDerivedIncomeIsRejectedNamingRealRequestFields() throws Exception {
        plan("""
                {"goal":{"type":"CAR","amount":1200,"mode":"YEARS","years":5},
                 "net_monthly_income":500,"monthly_tax_setaside":900,"monthly_expenses":100}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("net_monthly_income"),
                        org.hamcrest.Matchers.containsString("monthly_tax_setaside"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                                "income negali būti neigiamas")))));
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
