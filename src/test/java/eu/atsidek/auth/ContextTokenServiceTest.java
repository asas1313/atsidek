package eu.atsidek.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ADR-0003: Java pati išduoda ir pati patikrina trumpaamžį kontekstinį žetoną. Šis testas yra
 * ADR-0003's "mandatory negative test" vieta - suklastotas parašas IR atskirai pasibaigęs
 * galiojimas abu turi būti atmesti, nepriklausomai nuo to, kuri vėlesnė fazė tai realiai panaudos.
 */
class ContextTokenServiceTest {

    private static final String SECRET = "test-secret-at-least-this-long";
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    private static Clock fixedClock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private static ContextTokenService service(String secret, Instant now) {
        return new ContextTokenService(secret, fixedClock(now));
    }

    @Test
    void roundTripIssueThenVerifyClaimsMatch() {
        ContextTokenService svc = service(SECRET, NOW);
        UUID userId = UUID.randomUUID();

        String token = svc.issue(userId, "PREMIUM");
        var claims = svc.verify(token).orElseThrow();

        assertThat(claims.userId()).contains(userId);
        assertThat(claims.tier()).isEqualTo("PREMIUM");
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void anonymousUserIdIsNullAndRoundTripsAsEmpty() {
        ContextTokenService svc = service(SECRET, NOW);

        String token = svc.issue(null, "FREE");
        var claims = svc.verify(token).orElseThrow();

        assertThat(claims.userId()).isEmpty();
        assertThat(claims.tier()).isEqualTo("FREE");
    }

    @Test
    void forgedSignatureIsRejected() {
        ContextTokenService svc = service(SECRET, NOW);
        String token = svc.issue(UUID.randomUUID(), "PREMIUM");
        String[] parts = token.split("\\.", 2);

        // Pakeičiam parašo dalį suklastota, bet struktūriškai galiojančia base64url reikšme.
        String forgedSignature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not-the-real-signature-bytes".getBytes());
        String forged = parts[0] + "." + forgedSignature;

        assertThat(svc.verify(forged)).isEmpty();
    }

    @Test
    void tamperedPayloadIsRejectedEvenThoughSignatureFormatIsValid() {
        ContextTokenService svc = service(SECRET, NOW);
        String token = svc.issue(UUID.randomUUID(), "FREE");
        String[] parts = token.split("\\.", 2);

        // Pakeičiam payload'o dalį (pvz. kitas vartotojo id), bet paliekam ORIGINALŲ (dabar
        // nebeatitinkantį) parašą - tai turi būti atmesta, ne tyliai priimta su neteisingu tier'u.
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"userId\":null,\"tier\":\"PREMIUM\",\"exp\":9999999999}".getBytes());
        String tampered = tamperedPayload + "." + parts[1];

        assertThat(svc.verify(tampered)).isEmpty();
    }

    @Test
    void expiredTokenIsRejected() {
        ContextTokenService issuer = service(SECRET, NOW);
        String token = issuer.issue(UUID.randomUUID(), "PREMIUM");

        ContextTokenService verifierLater = service(SECRET, NOW.plus(Duration.ofMinutes(5)).plusSeconds(1));
        assertThat(verifierLater.verify(token)).isEmpty();
    }

    @Test
    void tokenStillValidJustBeforeExpiryIsAccepted() {
        ContextTokenService issuer = service(SECRET, NOW);
        String token = issuer.issue(UUID.randomUUID(), "PREMIUM");

        ContextTokenService verifierJustBefore = service(SECRET, NOW.plus(Duration.ofMinutes(4)).plusSeconds(59));
        assertThat(verifierJustBefore.verify(token)).isPresent();
    }

    @Test
    void issueWithoutSecretFailsLoudlyInsteadOfSilentlyUsingAnEmptySecret() {
        ContextTokenService svc = service("", NOW);
        assertThatThrows(() -> svc.issue(UUID.randomUUID(), "FREE"));
    }

    @Test
    void issueWithBlankSecretAlsoFailsLoudly() {
        ContextTokenService svc = service("   ", NOW);
        assertThatThrows(() -> svc.issue(null, "FREE"));
    }

    @Test
    void verifyWithoutSecretAlwaysRejectsEvenAGenuineToken() {
        ContextTokenService issuer = service(SECRET, NOW);
        String token = issuer.issue(UUID.randomUUID(), "PREMIUM");

        ContextTokenService misconfiguredVerifier = service("", NOW);
        assertThat(misconfiguredVerifier.verify(token)).isEmpty();
    }

    @Test
    void verifyRejectsMissingOrMalformedTokens() {
        ContextTokenService svc = service(SECRET, NOW);
        assertThat(svc.verify(null)).isEmpty();
        assertThat(svc.verify("")).isEmpty();
        assertThat(svc.verify("   ")).isEmpty();
        assertThat(svc.verify("no-dot-separator-here")).isEmpty();
        assertThat(svc.verify("not-base64!.also-not-base64!")).isEmpty();
    }

    @Test
    void verifyRejectsTokenSignedWithADifferentSecret() {
        ContextTokenService issuer = service("secret-a-is-long-enough", NOW);
        String token = issuer.issue(UUID.randomUUID(), "PREMIUM");

        ContextTokenService verifierWithOtherSecret = service("secret-b-is-long-enough", NOW);
        assertThat(verifierWithOtherSecret.verify(token)).isEmpty();
    }

    private static void assertThatThrows(Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // OK
        }
    }
}
