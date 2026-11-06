package eu.atsidek.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Visiems šablonams: ar vartotojas prisijungęs (meniu rodo „Prisijungti“ arba „Atsijungti“). */
@ControllerAdvice
public class AuthModelAdvice {

    @ModelAttribute("loggedIn")
    boolean loggedIn(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof OidcUser;
    }
}
