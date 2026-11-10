package eu.atsidek.goals.tracking.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@link SavingsGoalController} - entitlement patikra pirma ({@code 403}), tada savininkystei
 * apribota paieška ({@code 404}, niekada {@code 403} - IDOR), patvirtinimo eiga (PLAN.md §5/§6
 * task 10). Kiekvienam testo vartotojui {@code SAVINGS_TRACKING} suteikiama per tikrą
 * {@code entitlement} eilutę (ne globalų beta flag'ą), kad testai nepriklausytų nuo bendro
 * {@code EntitlementService} talpyklos lango per visą paketą.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SavingsGoalControllerTest {

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

    /**
     * SVARBU: {@code .authorities(...)} NEGALI būti kviečiamas po {@code .oidcUser(...)} -
     * {@code OidcLoginRequestPostProcessor.authorities(...)} iš naujo nustato savo vidinį
     * {@code oidcUser} tiekėją atgal į numatytąjį (atmesdamas čia perduotą principal'ą) - patikrinta
     * dekompiliavus {@code spring-security-test} baitkodą. {@link AppOidcUser#getAuthorities()}
     * pati grąžina teisingas roles, todėl papildomo {@code .authorities(...)} kvietimo nereikia.
     */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor loginAs(AppOidcUser user) {
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
    void savingWithoutEntitlementIsForbidden() throws Exception {
        AppOidcUser user = userWithoutAccess("no-access");
        MockHttpSession session = sessionWithPendingInput(sampleInput());

        mvc.perform(post("/goals/tikslai").with(loginAs(user)).with(csrf()).session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void firstSaveSucceedsUnconfirmed() throws Exception {
        AppOidcUser user = userWithAccess("first-save");
        MockHttpSession session = sessionWithPendingInput(sampleInput());

        mvc.perform(post("/goals/tikslai").with(loginAs(user)).with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("Tikslas išsaugotas")));

        // Sesijos atributas išvalytas po sėkmingo išsaugojimo.
        assertThat(session.getAttribute(SavingsGoalService.PENDING_INPUT_SESSION_KEY)).isNull();
        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM savings_goal g JOIN app_user u ON u.id = g.user_id WHERE u.provider_subject = ?",
                Integer.class, user.getName());
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void replaceWithoutConfirmLeavesGoalAndContributionsUntouched() throws Exception {
        AppOidcUser user = userWithAccess("needs-confirm");
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
        MvcResult result = mvc.perform(post("/goals/tikslai").with(loginAs(user)).with(csrf()).session(secondSession))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("pakeitus jos bus ištrintos");

        Integer contributionCount = jdbc.queryForObject(
                "SELECT count(*) FROM savings_contribution WHERE goal_id = ?", Integer.class, goalId);
        assertThat(contributionCount).isEqualTo(1);
        // Laukiantis skaičiavimas sesijoje PALIEKAMAS, kad "Pakeisti" galėtų veikti be pakartotinio siuntimo.
        assertThat(secondSession.getAttribute(SavingsGoalService.PENDING_INPUT_SESSION_KEY)).isNotNull();
    }

    @Test
    void replaceWithConfirmDeletesOldContributionsAndResetsProgress() throws Exception {
        AppOidcUser user = userWithAccess("confirm-replace");
        MockHttpSession firstSession = sessionWithPendingInput(sampleInput());
        mvc.perform(post("/goals/tikslai").with(loginAs(user)).with(csrf()).session(firstSession))
                .andExpect(status().isOk());

        UUID goalId = jdbc.queryForObject(
                "SELECT g.id FROM savings_goal g JOIN app_user u ON u.id = g.user_id WHERE u.provider_subject = ?",
                UUID.class, user.getName());
        mvc.perform(post(("/goals/tikslai/" + goalId + "/irasai")).with(loginAs(user)).with(csrf())
                        .param("month", "2026-01").param("amount", "100"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM savings_contribution WHERE goal_id = ?",
                Integer.class, goalId)).isEqualTo(1);

        MockHttpSession secondSession = sessionWithPendingInput(sampleInput());
        mvc.perform(post("/goals/tikslai").param("confirm", "true")
                        .with(loginAs(user)).with(csrf()).session(secondSession))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("Tikslas išsaugotas")));

        Integer goalRowCount = jdbc.queryForObject(
                "SELECT count(*) FROM savings_goal WHERE user_id = (SELECT id FROM app_user WHERE provider_subject = ?)",
                Integer.class, user.getName());
        assertThat(goalRowCount).isEqualTo(1); // tas pats id, ne antra eilutė
        Integer contributionCountAfter = jdbc.queryForObject(
                "SELECT count(*) FROM savings_contribution WHERE goal_id = ?", Integer.class, goalId);
        assertThat(contributionCountAfter).isEqualTo(0);
        assertThat(secondSession.getAttribute(SavingsGoalService.PENDING_INPUT_SESSION_KEY)).isNull();
    }

    @Test
    void recordContributionAndViewProgress() throws Exception {
        AppOidcUser user = userWithAccess("record-contribution");
        MockHttpSession session = sessionWithPendingInput(sampleInput());
        mvc.perform(post("/goals/tikslai").with(loginAs(user)).with(csrf()).session(session))
                .andExpect(status().isOk());
        UUID goalId = jdbc.queryForObject(
                "SELECT g.id FROM savings_goal g JOIN app_user u ON u.id = g.user_id WHERE u.provider_subject = ?",
                UUID.class, user.getName());

        mvc.perform(post(("/goals/tikslai/" + goalId + "/irasai")).with(loginAs(user)).with(csrf())
                        .param("month", "2026-01").param("amount", "50"))
                .andExpect(status().isOk());

        mvc.perform(get("/goals/tikslai/" + goalId).with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("Tikslas išsaugotas")));
    }

    @Test
    void viewingUnknownGoalIdIsNotFound() throws Exception {
        AppOidcUser user = userWithAccess("unknown-goal");

        mvc.perform(get("/goals/tikslai/" + UUID.randomUUID()).with(loginAs(user)))
                .andExpect(status().isNotFound());
    }

    /**
     * PLAN.md §6 task 1: taikinio suma validuojama programos lygmeniu (draugiškas 400), ne vien DB
     * {@code CHECK}. Tikrinama per TIKRĄ HTTP užklausą į valdiklį, ne vien per
     * {@code SavingsGoalServiceTest}'o mockintą store'ą - užtikrina, kad validacija realiai suveikia
     * prieš pasiekiant {@code SavingsGoalStore}, ne tik teoriškai servise.
     */
    @Test
    void recordingNegativeContributionAmountIsRejectedEndToEnd() throws Exception {
        AppOidcUser user = userWithAccess("neg-contribution");
        MockHttpSession session = sessionWithPendingInput(sampleInput());
        mvc.perform(post("/goals/tikslai").with(loginAs(user)).with(csrf()).session(session))
                .andExpect(status().isOk());
        UUID goalId = jdbc.queryForObject(
                "SELECT g.id FROM savings_goal g JOIN app_user u ON u.id = g.user_id WHERE u.provider_subject = ?",
                UUID.class, user.getName());

        mvc.perform(post(("/goals/tikslai/" + goalId + "/irasai")).with(loginAs(user)).with(csrf())
                        .param("month", "2026-01").param("amount", "-5"))
                .andExpect(status().isBadRequest());

        Integer contributionCount = jdbc.queryForObject(
                "SELECT count(*) FROM savings_contribution WHERE goal_id = ?", Integer.class, goalId);
        assertThat(contributionCount).isEqualTo(0);
    }

    @Test
    void recordingOversizedContributionAmountIsRejectedEndToEnd() throws Exception {
        AppOidcUser user = userWithAccess("oversized-contribution");
        MockHttpSession session = sessionWithPendingInput(sampleInput());
        mvc.perform(post("/goals/tikslai").with(loginAs(user)).with(csrf()).session(session))
                .andExpect(status().isOk());
        UUID goalId = jdbc.queryForObject(
                "SELECT g.id FROM savings_goal g JOIN app_user u ON u.id = g.user_id WHERE u.provider_subject = ?",
                UUID.class, user.getName());

        mvc.perform(post(("/goals/tikslai/" + goalId + "/irasai")).with(loginAs(user)).with(csrf())
                        .param("month", "2026-01").param("amount", "100000001"))
                .andExpect(status().isBadRequest());

        Integer contributionCount = jdbc.queryForObject(
                "SELECT count(*) FROM savings_contribution WHERE goal_id = ?", Integer.class, goalId);
        assertThat(contributionCount).isEqualTo(0);
    }

    /**
     * Neigiama/per didelė TIKSLO (ne įmokos) suma turi būti atmesta su {@code 400} dar prieš
     * pasiekiant {@code SavingsGoalStore} - {@code GoalsService.calculate} validacija (task 1)
     * veikia ir šiam, naujam, sesijos-perkėlimo keliui, ne vien senam {@code /goals/calculate}.
     */
    @Test
    void savingGoalWithNegativeAmountIsRejectedEndToEnd() throws Exception {
        AppOidcUser user = userWithAccess("neg-goal-amount");
        GoalInput negative = new GoalInput(GoalType.CAR, GoalMode.YEARS, new BigDecimal("-100"), 1, null, null, null,
                null, null, new Household(null, null, null, null, null),
                new Assumptions(new BigDecimal("0"), new BigDecimal("0")), null);
        MockHttpSession session = sessionWithPendingInput(negative);

        mvc.perform(post("/goals/tikslai").with(loginAs(user)).with(csrf()).session(session))
                .andExpect(status().isBadRequest());

        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM savings_goal g JOIN app_user u ON u.id = g.user_id WHERE u.provider_subject = ?",
                Integer.class, user.getName());
        assertThat(rowCount).isEqualTo(0);
    }

    /**
     * Regresijos testas: {@code amount = 0} anksčiau praeidavo pro {@code GoalsService.calculate}
     * validaciją (ji leidžia nulį - tinkama laisvai skaičiuoklei, bet NE išsaugotam tikslui) ir
     * sudužo su neapgaubta {@code DataIntegrityViolationException} ({@code savings_goal_amount_check},
     * {@code amount > 0}) - DB {@code CHECK} tapo VIENINTELE apsauga, ir tai HTTP {@code 500}, ne
     * draugišku {@code 400} (žr. ADR-0002/plano §1 principą - DB CHECK yra atsarginis variantas, ne
     * pirminė validacija). Tikrinama per TIKRĄ HTTP užklausą, kad patvirtintų realų elgesį, ne vien
     * mockintą servisą.
     */
    @Test
    void savingGoalWithZeroAmountIsRejectedEndToEnd() throws Exception {
        AppOidcUser user = userWithAccess("zero-goal-amount");
        GoalInput zero = new GoalInput(GoalType.CAR, GoalMode.YEARS, BigDecimal.ZERO, 1, null, null, null,
                null, null, new Household(null, null, null, null, null),
                new Assumptions(new BigDecimal("0"), new BigDecimal("0")), null);
        MockHttpSession session = sessionWithPendingInput(zero);

        mvc.perform(post("/goals/tikslai").with(loginAs(user)).with(csrf()).session(session))
                .andExpect(status().isBadRequest());

        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM savings_goal g JOIN app_user u ON u.id = g.user_id WHERE u.provider_subject = ?",
                Integer.class, user.getName());
        assertThat(rowCount).isEqualTo(0);
    }

    @Test
    void savingGoalWithOversizedAmountIsRejectedEndToEnd() throws Exception {
        AppOidcUser user = userWithAccess("oversized-goal-amount");
        GoalInput oversized = new GoalInput(GoalType.CAR, GoalMode.YEARS, new BigDecimal("100000001"), 1, null, null,
                null, null, null, new Household(null, null, null, null, null),
                new Assumptions(new BigDecimal("0"), new BigDecimal("0")), null);
        MockHttpSession session = sessionWithPendingInput(oversized);

        mvc.perform(post("/goals/tikslai").with(loginAs(user)).with(csrf()).session(session))
                .andExpect(status().isBadRequest());

        Integer rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM savings_goal g JOIN app_user u ON u.id = g.user_id WHERE u.provider_subject = ?",
                Integer.class, user.getName());
        assertThat(rowCount).isEqualTo(0);
    }

    @Test
    void viewingAnotherUsersGoalIsNotFoundNotForbidden() throws Exception {
        AppOidcUser owner = userWithAccess("idor-owner");
        AppOidcUser intruder = userWithAccess("idor-intruder");

        MockHttpSession session = sessionWithPendingInput(sampleInput());
        mvc.perform(post("/goals/tikslai").with(loginAs(owner)).with(csrf()).session(session))
                .andExpect(status().isOk());
        UUID ownerGoalId = jdbc.queryForObject(
                "SELECT g.id FROM savings_goal g JOIN app_user u ON u.id = g.user_id WHERE u.provider_subject = ?",
                UUID.class, owner.getName());

        mvc.perform(get("/goals/tikslai/" + ownerGoalId).with(loginAs(intruder)))
                .andExpect(status().isNotFound());

        // Pats savininkas tebematytą savo tikslą - skirtumas yra TIK dėl savininkystės, ne dėl id neteisingumo.
        mvc.perform(get("/goals/tikslai/" + ownerGoalId).with(loginAs(owner)))
                .andExpect(status().isOk());
    }

    /**
     * IDOR patikra taikoma TIEK {@code GET /goals/tikslai/{id}} (žiūrėjimas), TIEK
     * {@code POST /goals/tikslai/{id}/irasai} (įmokos žymėjimas) - abu naudoja tą pačią
     * savininkystei apribotą {@code findOwnGoal} užklausą, bet tai atskiras valdiklio metodas,
     * todėl tikrinama atskirai, kad nebūtų tylos "pamiršta šitam keliui" regresija.
     */
    @Test
    void recordingContributionOnAnotherUsersGoalIsNotFoundAndWritesNothing() throws Exception {
        AppOidcUser owner = userWithAccess("idor-irasai-owner");
        AppOidcUser intruder = userWithAccess("idor-irasai-intruder");

        MockHttpSession session = sessionWithPendingInput(sampleInput());
        mvc.perform(post("/goals/tikslai").with(loginAs(owner)).with(csrf()).session(session))
                .andExpect(status().isOk());
        UUID ownerGoalId = jdbc.queryForObject(
                "SELECT g.id FROM savings_goal g JOIN app_user u ON u.id = g.user_id WHERE u.provider_subject = ?",
                UUID.class, owner.getName());

        mvc.perform(post(("/goals/tikslai/" + ownerGoalId + "/irasai")).with(loginAs(intruder)).with(csrf())
                        .param("month", "2026-01").param("amount", "500"))
                .andExpect(status().isNotFound());

        Integer contributionCount = jdbc.queryForObject(
                "SELECT count(*) FROM savings_contribution WHERE goal_id = ?", Integer.class, ownerGoalId);
        assertThat(contributionCount).isEqualTo(0);
    }
}
