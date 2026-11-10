package eu.atsidek.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * {@code MissingAppUserFilter} (PLAN.md §6 task 15) - jei vartotojas ištrina paskyrą viename
 * naršyklės lange (task 13), kito lango/sesijos {@code SecurityContext} vis tiek turi galiojantį
 * {@code AppOidcUser} principal'ą, rodantį į jau neegzistuojantį {@code app_user.id}. Testas
 * simuliuoja šį scenarijų: ištrina {@code app_user} eilutę tiesia JDBC užklausa (ne per
 * {@code /paskyra/istrinti}, kad liktų aiškiai atskirtas "kita sesija" scenarijus), tada tuo pačiu
 * principal'u kviečia apsaugotą {@code ROLE_USER} kelią - tikimasi nukreipimo į "/" ir baigtos
 * sesijos, NE 404/500.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class MissingAppUserFilterTest {

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
    void requestAfterAppUserRowDeletedMidSessionRedirectsHomeAndInvalidatesSession() throws Exception {
        AppUser appUser = appUserStore.upsert(UUID.randomUUID(), "google", "subject-" + UUID.randomUUID(),
                "missing-app-user@example.com");
        AppOidcUser user = appOidcUser(appUser.id(), appUser.providerSubject());

        // Simuliuoja paskyros ištrynimą KITOJE sesijoje (task 13) - šios sesijos principal'as
        // (žemiau, loginAs(user)) vis tiek "galioja", bet app_user eilutė jau nebėra DB.
        jdbc.update("DELETE FROM app_user WHERE id = ?", appUser.id());

        MockHttpSession session = new MockHttpSession();

        mvc.perform(get("/paskyra").with(loginAs(user)).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        assertThat(session.isInvalid()).isTrue();
    }
}
