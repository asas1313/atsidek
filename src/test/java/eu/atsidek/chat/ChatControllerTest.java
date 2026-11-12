package eu.atsidek.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.atsidek.TestcontainersConfiguration;
import eu.atsidek.auth.AppOidcUser;
import eu.atsidek.auth.ContextTokenService;
import eu.atsidek.entitlement.AppUser;
import eu.atsidek.entitlement.AppUserStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /chat/send} (PLAN-taupymas-agentas.B.plan.md B1, ADR-0003). Kaip ir
 * {@code SavingsPlanApiControllerTest}, reikalauja tikro PostgreSQL (Testcontainers), nes
 * tapatybė/tier'as sprendžiamas per {@code SavingsPlanService}/{@code EntitlementService}.
 * {@code AssistantClient} - vienintelė šio endpoint'o tinklo priklausomybė - pakeista mock'u, kad
 * testas neitų per realų tinklą į Python servisą.
 */
@SpringBootTest(properties = "atsidek.chat.context-token-secret=test-context-token-secret")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ChatControllerTest {

    private static final String TOKEN_SECRET = "test-context-token-secret";

    @Autowired
    MockMvc mvc;
    @Autowired
    AppUserStore appUserStore;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    java.time.Clock clock;

    @MockitoBean
    AssistantClient assistantClient;

    private AppOidcUser userWithFullAccess(String label) {
        AppUser appUser = appUserStore.upsert(UUID.randomUUID(), "google", "subject-" + label + "-" + UUID.randomUUID(),
                label + "@example.com");
        for (String feature : List.of("SAVINGS_MULTI_GOAL", "SAVINGS_IV_LINK", "SAVINGS_SCENARIOS")) {
            jdbc.update("INSERT INTO entitlement (user_id, feature, source, valid_from) VALUES (?, ?, 'BETA', now())",
                    appUser.id(), feature);
        }
        return appOidcUser(appUser.id(), appUser.providerSubject());
    }

    private static AppOidcUser appOidcUser(UUID appUserId, String subject) {
        OidcIdToken idToken = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("sub", subject, "email", subject + "@example.com", "email_verified", true));
        DefaultOidcUser delegate = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("SCOPE_openid")), idToken);
        return new AppOidcUser(delegate, appUserId, false);
    }

    @Test
    void anonymousMessageIssuesTokenWithNoUserIdAndFreeTier() throws Exception {
        when(assistantClient.chat(anyString(), anyString())).thenReturn(Map.of("reply", "ack"));

        mvc.perform(post("/chat/send").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Kiek IV mokesčių sumoku nuo 2000 eurų?\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(assistantClient).chat(tokenCaptor.capture(), messageCaptor.capture());

        assertThat(messageCaptor.getValue()).isEqualTo("Kiek IV mokesčių sumoku nuo 2000 eurų?");
        var claims = new ContextTokenService(TOKEN_SECRET, clock).verify(tokenCaptor.getValue()).orElseThrow();
        assertThat(claims.userId()).isEmpty();
        assertThat(claims.tier()).isEqualTo("FREE");
    }

    @Test
    void loggedInUserWithAllGatedFeaturesGetsAPremiumTierTokenCarryingTheirUserId() throws Exception {
        AppOidcUser user = userWithFullAccess("chat-full-access");
        when(assistantClient.chat(anyString(), anyString())).thenReturn(Map.of("reply", "ack"));

        mvc.perform(post("/chat/send").with(oidcLogin().oidcUser(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Labas\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(assistantClient).chat(tokenCaptor.capture(), anyString());

        var claims = new ContextTokenService(TOKEN_SECRET, clock).verify(tokenCaptor.getValue()).orElseThrow();
        assertThat(claims.userId()).contains(user.appUserId());
        assertThat(claims.tier()).isEqualTo("PREMIUM");
    }

    @Test
    void missingCsrfTokenIsRejectedWith403DespitePermitAll() throws Exception {
        // ĖJA testai aukščiau visi su .with(csrf()) - įrodo, kad permitAll() autorizacijai NEATŠAUKIA
        // CSRF apsaugos (ji - kitas sluoksnis, žr. ADR-0003/PLAN-taupymas-agentas.B.plan.md B1).
        mvc.perform(post("/chat/send").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Labas\"}"))
                .andExpect(status().isForbidden());

        verify(assistantClient, org.mockito.Mockito.never()).chat(anyString(), any());
    }

    @Test
    void blankMessageIsRejectedWith400BeforeCallingAssistant() throws Exception {
        mvc.perform(post("/chat/send").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"   \"}"))
                .andExpect(status().isBadRequest());

        verify(assistantClient, org.mockito.Mockito.never()).chat(anyString(), any());
    }

    @Test
    void surfacesWhateverThePythonSideReturns() throws Exception {
        when(assistantClient.chat(anyString(), anyString())).thenReturn(Map.of("reply", "ack"));

        mvc.perform(post("/chat/send").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"Labas\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.reply")
                        .value("ack"));
    }
}
