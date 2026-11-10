package eu.atsidek.auth;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * {@link OidcUser} apvalkalas, pridedantis vidinį {@code app_user.id} (UUID) ir pakeičiantis
 * {@link #getAuthorities()} - visada {@code ROLE_USER}, papildomai {@code ROLE_ADMIN}, jei el.
 * paštas yra leidžiamų/savininko sąraše (žr. ADR-0001). Viskas kitą delegavoja tikram
 * {@link OidcUser}, kad {@code sub}/{@code email}/{@code email_verified} ir pan. liktų teisingi
 * (jie - default metodai, skaitantys {@link #getClaims()}).
 */
public class AppOidcUser implements OidcUser {

    private final OidcUser delegate;
    private final UUID appUserId;
    private final boolean admin;

    public AppOidcUser(OidcUser delegate, UUID appUserId, boolean admin) {
        this.delegate = delegate;
        this.appUserId = appUserId;
        this.admin = admin;
    }

    public UUID appUserId() {
        return appUserId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (admin) {
            return List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public Map<String, Object> getAttributes() {
        return delegate.getAttributes();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public Map<String, Object> getClaims() {
        return delegate.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return delegate.getUserInfo();
    }

    @Override
    public OidcIdToken getIdToken() {
        return delegate.getIdToken();
    }
}
