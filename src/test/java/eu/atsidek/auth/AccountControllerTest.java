package eu.atsidek.auth;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * {@code AccountController} (PLAN.md §6 task 13) - savitarnos paskyros puslapis ir trynimas,
 * pasikliaujantis {@code ON DELETE CASCADE} (ADR-0002) visoms keturioms lentelėms.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountControllerTest {

    @Autowired MockMvc mvc;
    @Autowired AppUserStore appUserStore;
    @Autowired JdbcTemplate jdbc;

    private static AppOidcUser appOidcUser(UUID appUserId, String subject) {
        OidcIdToken idToken = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("sub", subject, "email", subject + "@example.com", "email_verified", true));
        DefaultOidcUser delegate = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("SCOPE_openid")), idToken);
        return new AppOidcUser(delegate, appUserId, false);
    }

    private static RequestPostProcessor loginAs(AppOidcUser user) {
        return oidcLogin().oidcUser(user);
    }

    @Test
    void getPaskyraShowsEmail() throws Exception {
        AppUser appUser = appUserStore.upsert(UUID.randomUUID(), "google", "subject-" + UUID.randomUUID(),
                "mano-paskyra@example.com");
        AppOidcUser user = appOidcUser(appUser.id(), appUser.providerSubject());

        mvc.perform(get("/paskyra").with(loginAs(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("mano-paskyra@example.com")));
    }

    /**
     * Ištrinimas pašalina eilutes IŠ VISŲ KETURIŲ lentelių (app_user, entitlement, savings_goal,
     * savings_contribution) - tikrinama tiesia JDBC užklausa, ne vien HTTP atsakymu (kaip IDOR ir
     * įmokų trynimo testai anksčiau) - ir baigia sesiją, nukreipia į "/".
     */
    @Test
    void deleteRemovesAllFourTablesRowsAndEndsSessionAndRedirectsHome() throws Exception {
        AppUser appUser = appUserStore.upsert(UUID.randomUUID(), "google", "subject-" + UUID.randomUUID(),
                "delete-cascade@example.com");
        UUID userId = appUser.id();
        jdbc.update("INSERT INTO entitlement (user_id, feature, source, valid_from) VALUES (?, 'SAVINGS_TRACKING', "
                + "'BETA', now())", userId);
        UUID goalId = UUID.randomUUID();
        jdbc.update("INSERT INTO savings_goal (id, user_id, goal_type, amount, assumptions) "
                + "VALUES (?, ?, 'CAR', 100, '{}'::jsonb)", goalId, userId);
        jdbc.update("INSERT INTO savings_contribution (goal_id, month, amount) VALUES (?, '2026-01-01', 50)", goalId);

        AppOidcUser user = appOidcUser(userId, appUser.providerSubject());
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/paskyra/istrinti").with(loginAs(user)).with(csrf()).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        assertThat(session.isInvalid()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id = ?", Integer.class, userId))
                .isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entitlement WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM savings_goal WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM savings_contribution WHERE goal_id = ?", Integer.class,
                goalId)).isEqualTo(0);
    }

    @Test
    void anonymousPostToDeleteIsRejected() throws Exception {
        mvc.perform(post("/paskyra/istrinti")).andExpect(status().is4xxClientError());
    }
}
