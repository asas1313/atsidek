package eu.atsidek.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Visiems šablonams: ar vartotojas prisijungęs (meniu rodo „Prisijungti“ arba „Atsijungti“), ir ar
 * jis ROLE_ADMIN (meniu rodo/slepia banko modulio nuorodas ROLE_USER-only sesijoms, PLAN.md §6
 * task 12 - banko modulio apsauga pati nepasikeičia, tik nav nuorodos).
 */
@ControllerAdvice
public class AuthModelAdvice {

    @ModelAttribute("loggedIn")
    boolean loggedIn(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof OidcUser;
    }

    @ModelAttribute("isAdmin")
    boolean isAdmin(Authentication authentication) {
        return authentication != null
                && AuthorityUtils.authorityListToSet(authentication.getAuthorities()).contains("ROLE_ADMIN");
    }
}
