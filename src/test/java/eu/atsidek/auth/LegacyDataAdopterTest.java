package eu.atsidek.auth;

import static org.assertj.core.api.Assertions.assertThat;

import eu.atsidek.TestcontainersConfiguration;
import eu.atsidek.bank.CurrentUser;
import eu.atsidek.bank.store.BankStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/**
 * {@link LegacyDataAdopter} - jau perskaitytas, nepakitusios elgsenos (žr. ADR-0001, plano §6
 * task 8): {@code @EventListener} ant {@code AuthenticationSuccessEvent}, kuris perkelia senus
 * "local" banko duomenis naujam savininkui TIK kai patvirtintas prisijungimo el. paštas tiksliai
 * sutampa su {@code atsidek.auth.owner-email}. Šis test'as patvirtina, kad atidarius prisijungimą
 * visuomenei (ADR-0001), ši sąlyga liko nepakitusi: ne-savininko prisijungimas nieko neperima, o
 * savininko - taip, kaip ir anksčiau.
 *
 * <p>{@code AuthenticationSuccessEvent} iškviečiamas per tikrą {@code ApplicationEventPublisher}
 * (ne tiesiog kviečiant {@code onLogin(...)} metodą), kad testas patikrintų ir pačią {@code
 * @EventListener} laidų sujungimo vietą, ne vien logiką. MockMvc {@code oidcLogin()} čia
 * netinka - jis tiesiog įdeda principal'ą į SecurityContext, neprileidžiant realaus
 * autentifikacijos proceso, tad {@code AuthenticationSuccessEvent} per jį niekada neiškviečiamas.
 */
@SpringBootTest(properties = "atsidek.auth.owner-email=owner@example.com")
@Import(TestcontainersConfiguration.class)
class LegacyDataAdopterTest {

    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String OWNER_SUBJECT = "owner-sub";
    private static final String NON_OWNER_SUBJECT = "non-owner-sub";

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    BankStore store;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        store.deleteAll("local");
        store.deleteAll(CurrentUser.PREFIX + OWNER_SUBJECT);
        store.deleteAll(CurrentUser.PREFIX + NON_OWNER_SUBJECT);
    }

    @Test
    void nonOwnerVerifiedLoginDoesNotAdoptAndSeesNoLegacyData() {
        store.createAccount("local", "LT000000000000000001", "hash-local-1", "Senas", "SEB");

        publisher.publishEvent(new AuthenticationSuccessEvent(
                oidcAuthentication(NON_OWNER_SUBJECT, "someone-else@example.com", true)));

        assertThat(store.accounts(CurrentUser.PREFIX + NON_OWNER_SUBJECT)).isEmpty();
        assertThat(store.accounts("local")).hasSize(1);
    }

    @Test
    void ownerVerifiedLoginStillAdoptsLegacyDataUnchanged() {
        store.createAccount("local", "LT000000000000000002", "hash-local-2", "Senas", "SEB");

        publisher.publishEvent(new AuthenticationSuccessEvent(
                oidcAuthentication(OWNER_SUBJECT, OWNER_EMAIL, true)));

        assertThat(store.accounts(CurrentUser.PREFIX + OWNER_SUBJECT)).hasSize(1);
        assertThat(store.accounts("local")).isEmpty();
    }

    @Test
    void ownerLoginWithUnverifiedEmailDoesNotAdopt() {
        store.createAccount("local", "LT000000000000000003", "hash-local-3", "Senas", "SEB");

        publisher.publishEvent(new AuthenticationSuccessEvent(
                oidcAuthentication(OWNER_SUBJECT, OWNER_EMAIL, false)));

        assertThat(store.accounts(CurrentUser.PREFIX + OWNER_SUBJECT)).isEmpty();
        assertThat(store.accounts("local")).hasSize(1);
    }

    private static Authentication oidcAuthentication(String subject, String email, boolean emailVerified) {
        OidcIdToken idToken = new OidcIdToken("token-value", Instant.now(), Instant.now().plusSeconds(3600),
                Map.of("sub", subject, "email", email, "email_verified", emailVerified));
        DefaultOidcUser user = new DefaultOidcUser(List.of(), idToken);
        return new OAuth2AuthenticationToken(user, List.of(), "google");
    }
}
