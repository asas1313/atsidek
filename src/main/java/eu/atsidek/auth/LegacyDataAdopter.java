package eu.atsidek.auth;

import eu.atsidek.bank.CurrentUser;
import eu.atsidek.bank.store.BankStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/** Kai prisijungia savininkas (atsidek.auth.owner-email), jam perduodami seni vieno vartotojo („local“) duomenys. */
@Component
public class LegacyDataAdopter {

    private final BankStore store;
    private final String owner;

    public LegacyDataAdopter(BankStore store, @Value("${atsidek.auth.owner-email:}") String owner) {
        this.store = store;
        this.owner = owner.trim().toLowerCase();
    }

    @EventListener
    public void onLogin(AuthenticationSuccessEvent e) {
        if (owner.isEmpty() || !(e.getAuthentication().getPrincipal() instanceof OidcUser u)) return;
        if (u.getEmail() != null && owner.equals(u.getEmail().toLowerCase()) && Boolean.TRUE.equals(u.getEmailVerified())) {
            store.adoptLegacy("local", CurrentUser.PREFIX + u.getSubject());
        }
    }
}
