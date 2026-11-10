package eu.atsidek.goals.tracking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.atsidek.TestcontainersConfiguration;
import eu.atsidek.auth.AppOidcUser;
import eu.atsidek.entitlement.AppUser;
import eu.atsidek.entitlement.AppUserStore;
import eu.atsidek.goals.Assumptions;
import eu.atsidek.goals.GoalInput;
import eu.atsidek.goals.GoalMode;
import eu.atsidek.goals.GoalType;
import eu.atsidek.goals.Household;
import eu.atsidek.goals.tracking.SavingsGoalService;
import java.math.BigDecimal;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@link ManoTikslasController} - sesijos perkėlimo trijų šakų logika, kuri NIEKADA tyliai
 * nepakeičia esamo tikslo (PLAN.md §5/§6 task 11, blokuojantis pataisymas).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ManoTikslasControllerTest {

    @Autowired MockMvc mvc;
    @Autowired AppUserStore appUserStore;
    @Autowired JdbcTemplate jdbc;

    private AppOidcUser userWithAccess(String label) {
        AppUser appUser = appUserStore.upsert(UUID.randomUUID(), "google", "subject-" + label + "-" + UUID.randomUUID(),
                label + "@example.com");
        jdbc.update("INSERT INTO entitlement (user_id, feature, source, valid_from) VALUES (?, 'SAVINGS_TRACKING', "
                + "'BETA', now())", appUser.id());
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

    private static RequestPostProcessor loginAs(AppOidcUser user) {
        return oidcLogin().oidcUser(user);
    }

    private static GoalInput sampleInput() {
        return new GoalInput(GoalType.CAR, GoalMode.YEARS, new BigDecimal("1200"), 1, null, null, null, null, null,
                new Household(null, null, null, null, null),
                new Assumptions(new BigDecimal("0"), new BigDecimal("0")), null);
    }

    private static MockHttpSession sessionWithPendingInput(GoalInput input) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SavingsGoalService.PENDING_INPUT_SESSION_KEY, input);
        return session;
    }

    @Test
    void withoutEntitlementShowsNoAccessMessage() throws Exception {
        AppOidcUser user = userWithoutAccess("no-access");

        mvc.perform(get("/mano-tikslas").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("beta")));
    }

    @Test
    void noPendingAndNoGoalShowsEmptyState() throws Exception {
        AppOidcUser user = userWithAccess("empty-state");

        mvc.perform(get("/mano-tikslas").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Dar neturi išsaugoto")));
    }

    @Test
    void pendingInputWithNoExistingGoalAutoSavesAndClearsSession() throws Exception {
        AppOidcUser user = userWithAccess("auto-save");
        MockHttpSession session = sessionWithPendingInput(sampleInput());

        mvc.perform(get("/mano-tikslas").with(loginAs(user)).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tikslas išsaugotas")));

        assertThat(session.getAttribute(SavingsGoalService.PENDING_INPUT_SESSION_KEY)).isNull();
        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM savings_goal g JOIN app_user u ON u.id = g.user_id WHERE u.provider_subject = ?",
                Integer.class, user.getName());
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void pendingInputWithExistingGoalRendersChoiceAndTouchesNothing() throws Exception {
        AppOidcUser user = userWithAccess("choice");
        // Pirma pats tikslas, tiesiai per /goals/tikslai (task 10), tada pažymima įmoka.
        MockHttpSession firstSession = sessionWithPendingInput(sampleInput());
        mvc.perform(post("/goals/tikslai").with(loginAs(user)).with(csrf()).session(firstSession))
                .andExpect(status().isOk());
        UUID goalId = jdbc.queryForObject(
                "SELECT g.id FROM savings_goal g JOIN app_user u ON u.id = g.user_id WHERE u.provider_subject = ?",
                UUID.class, user.getName());
        mvc.perform(post(("/goals/tikslai/" + goalId + "/irasai")).with(loginAs(user)).with(csrf())
                        .param("month", "2026-01").param("amount", "100"))
                .andExpect(status().isOk());

        MockHttpSession secondSession = sessionWithPendingInput(sampleInput());
        mvc.perform(get("/mano-tikslas").with(loginAs(user)).session(secondSession))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Pakeisti")))
                .andExpect(content().string(containsString("Palikti esamą")));

        // Nei tikslas, nei sesijos atributas nepaliesti, kol vartotojas pats nepasirenka.
        assertThat(secondSession.getAttribute(SavingsGoalService.PENDING_INPUT_SESSION_KEY)).isNotNull();
        Integer contributionCount = jdbc.queryForObject(
                "SELECT count(*) FROM savings_contribution WHERE goal_id = ?", Integer.class, goalId);
        assertThat(contributionCount).isEqualTo(1);
    }

    @Test
    void rejectingPendingInputClearsSessionAndLeavesGoalUntouched() throws Exception {
        AppOidcUser user = userWithAccess("reject-pending");
        MockHttpSession firstSession = sessionWithPendingInput(sampleInput());
        mvc.perform(post("/goals/tikslai").with(loginAs(user)).with(csrf()).session(firstSession))
                .andExpect(status().isOk());
        UUID goalId = jdbc.queryForObject(
                "SELECT g.id FROM savings_goal g JOIN app_user u ON u.id = g.user_id WHERE u.provider_subject = ?",
                UUID.class, user.getName());

        MockHttpSession secondSession = sessionWithPendingInput(sampleInput());
        mvc.perform(post("/goals/tikslai/laukiantis/atmesti").with(loginAs(user)).with(csrf()).session(secondSession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/mano-tikslas"));

        assertThat(secondSession.getAttribute(SavingsGoalService.PENDING_INPUT_SESSION_KEY)).isNull();
        String goalTypeAfter = jdbc.queryForObject("SELECT goal_type FROM savings_goal WHERE id = ?",
                String.class, goalId);
        assertThat(goalTypeAfter).isEqualTo("CAR"); // nepakeista
    }

    @Test
    void preliminarusStoresValidatedInputInSessionForSubsequentManoTikslasRequest() throws Exception {
        AppOidcUser user = userWithAccess("preliminarus-flow");
        MockHttpSession session = new MockHttpSession();
        String body = "{\"goalType\":\"CAR\",\"mode\":\"YEARS\",\"amount\":1200,\"years\":1,"
                + "\"assumptions\":{\"annualReturn\":0,\"annualInflation\":0}}";

        mvc.perform(post("/goals/tikslai/preliminarus").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mvc.perform(get("/mano-tikslas").with(loginAs(user)).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tikslas išsaugotas")));
    }

    @Test
    void preliminarusRejectsInvalidInput() throws Exception {
        mvc.perform(post("/goals/tikslai/preliminarus").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
