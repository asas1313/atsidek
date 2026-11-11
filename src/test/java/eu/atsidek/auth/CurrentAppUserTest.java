package eu.atsidek.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/**
 * Pure (be Spring konteksto) {@link CurrentAppUser#optionalId()} atvejai - naujas, neišmetantis
 * metodas, reikalingas A3 (taupymo plano API), kur anonimas yra teisėtas, ne klaidingas atvejis
 * (žr. PLAN-taupymas-agentas.md A4 sprendimas #6), skirtingai nuo {@link CurrentAppUser#id()},
 * kuris lieka nepakitęs esamiems ROLE_USER keliams.
 */
class CurrentAppUserTest {

    private final CurrentAppUser currentAppUser = new CurrentAppUser();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsEmptyWhenNoAuthenticationInContext() {
        SecurityContextHolder.clearContext();

        assertThat(currentAppUser.optionalId()).isEmpty();
    }

    @Test
    void returnsEmptyForAnonymousAuthentication() {
        AnonymousAuthenticationToken anon = new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(anon);

        assertThat(currentAppUser.optionalId()).isEmpty();
    }

    @Test
    void returnsIdForAppOidcUserPrincipal() {
        UUID appUserId = UUID.randomUUID();
        OidcIdToken idToken = new OidcIdToken("token", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("sub", "subject-1", "email", "subject-1@example.com", "email_verified", true));
        DefaultOidcUser delegate = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("SCOPE_openid")), idToken);
        AppOidcUser user = new AppOidcUser(delegate, appUserId, false);
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.TestingAuthenticationToken(user, null, "ROLE_USER"));

        assertThat(currentAppUser.optionalId()).contains(appUserId);
    }
}
