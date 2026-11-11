package eu.atsidek.auth;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Prisijungusio vartotojo vidinis {@code app_user.id} (UUID), analogiškai {@code bank.CurrentUser}
 * (google sub stringas) - čia tikras UUID, skaitomas tiesiai nuo principal'o (PLAN.md §6 task 2/4),
 * be papildomo DB skaitymo per kiekvieną užklausimą. Kviesti tik apsaugotuose (ROLE_USER) keliuose.
 */
@Component
public class CurrentAppUser {

    public UUID id() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AppOidcUser u) {
            return u.appUserId();
        }
        throw new IllegalStateException("Vartotojas neprisijungęs");
    }
}
