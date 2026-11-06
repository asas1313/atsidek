package eu.atsidek.bank;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/** Prisijungusio vartotojo ID (Google {@code sub}). Kviesti tik apsaugotuose keliuose. */
@Component
public class CurrentUser {

    public static final String PREFIX = "google:";

    public String id() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof OidcUser u) {
            return PREFIX + u.getSubject();
        }
        throw new IllegalStateException("Vartotojas neprisijungęs");
    }
}
