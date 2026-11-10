package eu.atsidek.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import eu.atsidek.TestcontainersConfiguration;
import eu.atsidek.entitlement.AppUserStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * {@link AppOidcUserService} - provisioning vyksta tiesiai {@code loadUser()} metode (ne success
 * handler/advice/"pirmo request" gudrybe), žr. ADR-0001. Delegatas mockinamas, kad nebūtų tikro
 * tinklo kvietimo į userinfo endpointą; {@link AppUserStore} - realus, Testcontainers Postgres,
 * nes testo tikslas - patikrinti, kad eilutė sukuriama ir id stabilus.
 */
@SuppressWarnings("unchecked")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AppOidcUserServiceTest {

    @Autowired
    AppUserStore appUserStore;

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate = mock(OAuth2UserService.class);

    @Test
    void verifiedNonAllowListedLoginGetsOnlyRoleUserAndCreatesRow() {
        AppOidcUserService service = new AppOidcUserService(delegate, appUserStore, "", "");
        String subject = "subject-" + UUID.randomUUID();
        when(delegate.loadUser(any())).thenReturn(fakeOidcUser(subject, "newuser@example.com", true));

        OidcUser result = service.loadUser(oidcUserRequest());

        assertThat(result).isInstanceOf(AppOidcUser.class);
        AppOidcUser appOidcUser = (AppOidcUser) result;
        assertThat(authorityNames(appOidcUser)).containsExactly("ROLE_USER");

        var stored = appUserStore.findByProviderSubject("google", subject);
        assertThat(stored).isPresent();
        assertThat(stored.get().id()).isEqualTo(appOidcUser.appUserId());
        assertThat(stored.get().email()).isEqualTo("newuser@example.com");
    }

    @Test
    void verifiedAllowListedLoginGetsBothRoles() {
        AppOidcUserService service = new AppOidcUserService(delegate, appUserStore, "owner@example.com", "");
        String subject = "subject-" + UUID.randomUUID();
        when(delegate.loadUser(any())).thenReturn(fakeOidcUser(subject, "owner@example.com", true));

        OidcUser result = service.loadUser(oidcUserRequest());

        AppOidcUser appOidcUser = (AppOidcUser) result;
        assertThat(authorityNames(appOidcUser)).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void ownerEmailConfigAlsoGrantsAdmin() {
        AppOidcUserService service = new AppOidcUserService(delegate, appUserStore, "", "owner2@example.com");
        String subject = "subject-" + UUID.randomUUID();
        when(delegate.loadUser(any())).thenReturn(fakeOidcUser(subject, "owner2@example.com", true));

        OidcUser result = service.loadUser(oidcUserRequest());

        AppOidcUser appOidcUser = (AppOidcUser) result;
        assertThat(authorityNames(appOidcUser)).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void unverifiedEmailIsStillRejected() {
        AppOidcUserService service = new AppOidcUserService(delegate, appUserStore, "", "");
        when(delegate.loadUser(any()))
                .thenReturn(fakeOidcUser("subject-" + UUID.randomUUID(), "unverified@example.com", false));

        assertThatThrownBy(() -> service.loadUser(oidcUserRequest()))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void repeatLoginKeepsSameAppUserId() {
        AppOidcUserService service = new AppOidcUserService(delegate, appUserStore, "", "");
        String subject = "subject-" + UUID.randomUUID();
        when(delegate.loadUser(any())).thenReturn(fakeOidcUser(subject, "a@example.com", true));
        AppOidcUser first = (AppOidcUser) service.loadUser(oidcUserRequest());

        when(delegate.loadUser(any())).thenReturn(fakeOidcUser(subject, "b@example.com", true));
        AppOidcUser second = (AppOidcUser) service.loadUser(oidcUserRequest());

        assertThat(second.appUserId()).isEqualTo(first.appUserId());
    }

    private static List<String> authorityNames(AppOidcUser user) {
        return user.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    private static OidcUser fakeOidcUser(String subject, String email, boolean emailVerified) {
        OidcIdToken idToken = new OidcIdToken("token-value", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("sub", subject, "email", email, "email_verified", emailVerified));
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("SCOPE_openid")), idToken);
    }

    private static OidcUserRequest oidcUserRequest() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("google")
                .clientId("test-client")
                .clientSecret("test-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userNameAttributeName("sub")
                .clientName("Google")
                .build();
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "access-token-value", Instant.now(),
                Instant.now().plusSeconds(3600));
        OidcIdToken idToken = new OidcIdToken("token-value", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("sub", "irrelevant"));
        return new OidcUserRequest(registration, accessToken, idToken);
    }
}
