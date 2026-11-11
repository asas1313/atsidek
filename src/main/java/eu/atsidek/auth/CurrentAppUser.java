package eu.atsidek.auth;

import java.util.Optional;
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
        return optionalId().orElseThrow(() -> new IllegalStateException("Vartotojas neprisijungęs"));
    }

    /**
     * Anonimas - teisėtas atvejis šiam metodui (PLAN-taupymas-agentas.md A3/A4 sprendimas #6: tier
     * viešiems/anoniminiams kvietimams sprendžiamas pagal sesijos nebuvimą, ne klaidą). Naudoti
     * keliuose, kur ir prisijungęs, ir anoniminis vartotojas yra teisėti (pvz. {@code POST
     * /api/v1/savings/plan}), skirtingai nuo {@link #id()}, kuris lieka griežtas esamiems ROLE_USER
     * keliams.
     */
    public Optional<UUID> optionalId() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AppOidcUser u) {
            return Optional.of(u.appUserId());
        }
        return Optional.empty();
    }
}
