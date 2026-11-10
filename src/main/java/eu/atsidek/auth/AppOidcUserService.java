package eu.atsidek.auth;

import eu.atsidek.entitlement.AppUser;
import eu.atsidek.entitlement.AppUserStore;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Pakeičia ankstesnį inline {@code oidcUserService} beaną (žr. ADR-0001). {@code loadUser()} - ir
 * tik jis - tiesiogiai iškviečia {@link AppUserStore#upsert}; čia yra vienintelis provisioning
 * punktas (ne success handler, ne advice, ne "pirmo request" gudrybė). Priima kiekvieną patvirtintą
 * (email_verified) prisijungimą - ROLE_USER visiems, ROLE_ADMIN papildomai, jei el. paštas yra
 * leidžiamų/savininko sąraše. Neprileidžia tik nepatvirtinto el. pašto (nepakitusi elgsena).
 */
public class AppOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final AppUserStore appUserStore;
    private final Set<String> adminEmails;

    public AppOidcUserService(
            OAuth2UserService<OidcUserRequest, OidcUser> delegate, AppUserStore appUserStore,
            String allowedEmails, String ownerEmail) {
        this.delegate = delegate;
        this.appUserStore = appUserStore;
        this.adminEmails = Arrays.stream((allowedEmails + "," + ownerEmail).split(","))
                .map(s -> s.trim().toLowerCase())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = delegate.loadUser(userRequest);
        if (!Boolean.TRUE.equals(oidcUser.getEmailVerified())) {
            throw new OAuth2AuthenticationException(new OAuth2Error("access_denied"), "El. paštas neleidžiamas");
        }
        String email = oidcUser.getEmail() == null ? "" : oidcUser.getEmail().toLowerCase();
        String authProvider = userRequest.getClientRegistration().getRegistrationId();
        AppUser appUser = appUserStore.upsert(UUID.randomUUID(), authProvider, oidcUser.getSubject(), email);
        boolean admin = adminEmails.contains(email);
        return new AppOidcUser(oidcUser, appUser.id(), admin);
    }
}
