package eu.atsidek.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Trumpaamžis (~5 min), šios pačios Java pusės pasirašytas ir patikrinamas kontekstinis žetonas
 * Java→Python→Java tapatybės relei (žr. docs/adr/0003-ai-agent-python-java-service-identity.md,
 * "Accepted"). Naujas chat endpoint'as (žr. {@code eu.atsidek.chat.ChatController}) šiuo žetonu
 * {@code {user_id, tier, exp}} pasako Python {@code assistant} servisui, kuriam vartotojui jis
 * veikia; Python žetono NEI tikrina, NEI dekoduoja - tik grąžina jį NEPAKEISTĄ ant kiekvieno
 * įrankio (mokesčių/taupymo) kvietimo atgal į Java, o Java ČIA (šiame servise) parašą ir galiojimą
 * patikrina iš naujo prieš vykdydama tą kvietimą. Python niekada neturi ir negali turėti šio
 * servisų paslapties - jis tik relė, niekada tapatybės savininkas.
 * <p>
 * Pasirašymas - HMAC-SHA256 per JDK pačio {@code javax.crypto} (NE jjwt/nimbus-jose ir pan. - sąmoningai
 * nepridėta naujos Maven priklausomybės, žr. PLAN-taupymas-agentas.B.plan.md B1 "No new dependency").
 * Formatas: {@code base64url(JSON payload) + "." + base64url(HMAC-SHA256 parašas virš JSON baitų)}.
 * Patikra lygina parašus su {@link MessageDigest#isEqual} (pastovaus laiko palyginimas, tas pats
 * raštas kaip {@link ApiKeyFilter#matches}), o galiojimo laiką - su įpurškiamu {@link Clock} (ADR-0002).
 * <p>
 * Fail-closed, tiksliai kaip {@link ApiKeyFilter} ("Jei maišų nenurodyta - API uždarytas"): jei
 * {@code atsidek.chat.context-token-secret} (env {@code ATSIDEK_CHAT_CONTEXT_TOKEN_SECRET} - NE
 * {@code ATSIDEK_CHAT_TOKEN_SECRET}; ankstesnė pavadinimo klaida, dėl kurios Spring'o relaxed
 * env-var binding šiai savybei tyliai nesuveikia - žr. B6 gyvo eval'o incidento logą) nenurodyta ar
 * tuščia - {@link #issue} garsiai meta {@link IllegalStateException} (žetonas niekada neišduodamas
 * su tuščia/numatytąja paslaptimi), o {@link #verify} visada grąžina {@code Optional.empty()}
 * (žetonas niekada nepriimamas, net jei jis realiai buvo pasirašytas kažkada veikiančia paslaptimi) -
 * niekada tyliai neketa veikti su silpnesne nei sukonfigūruota apsauga.
 */
@Component
public class ContextTokenService {

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final String secret;
    private final Clock clock;

    public ContextTokenService(@Value("${atsidek.chat.context-token-secret:}") String secret, Clock clock) {
        this.secret = secret;
        this.clock = clock;
    }

    /** Patikrinto žetono turinys - {@code userId} tuščias teisėtam anonimo atvejui (ADR-0003). */
    public record Claims(Optional<UUID> userId, String tier, Instant expiresAt) {
    }

    /**
     * @throws IllegalStateException jei paslaptis nesukonfigūruota - fail closed, ne tylus numatytasis raktas.
     */
    public String issue(UUID userId, String tier) {
        requireSecret();
        long exp = clock.instant().plus(TTL).getEpochSecond();
        byte[] payloadBytes = writePayload(userId, tier, exp);
        String payloadPart = ENCODER.encodeToString(payloadBytes);
        String signaturePart = ENCODER.encodeToString(hmac(payloadBytes));
        return payloadPart + "." + signaturePart;
    }

    /** Niekada meta išimties - bloga/pasenusi/suklastota įvestis visada tiesiog {@code Optional.empty()}. */
    public Optional<Claims> verify(String token) {
        if (secret == null || secret.isBlank() || token == null || token.isBlank()) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot < 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        byte[] payloadBytes;
        byte[] givenSignature;
        try {
            payloadBytes = DECODER.decode(token.substring(0, dot));
            givenSignature = DECODER.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException malformedBase64) {
            return Optional.empty();
        }
        byte[] expectedSignature = hmac(payloadBytes);
        if (!MessageDigest.isEqual(expectedSignature, givenSignature)) {
            return Optional.empty();
        }
        return parseClaims(payloadBytes);
    }

    private Optional<Claims> parseClaims(byte[] payloadBytes) {
        JsonNode node;
        try {
            node = MAPPER.readTree(payloadBytes);
        } catch (Exception malformedJson) {
            return Optional.empty();
        }
        if (!node.hasNonNull("exp") || !node.has("tier")) {
            return Optional.empty();
        }
        Instant expiresAt = Instant.ofEpochSecond(node.get("exp").asLong());
        if (!expiresAt.isAfter(clock.instant())) {
            return Optional.empty();
        }
        JsonNode userIdNode = node.get("userId");
        Optional<UUID> userId;
        try {
            userId = (userIdNode == null || userIdNode.isNull())
                    ? Optional.empty()
                    : Optional.of(UUID.fromString(userIdNode.asText()));
        } catch (IllegalArgumentException invalidUuid) {
            return Optional.empty();
        }
        String tier = node.get("tier").asText();
        return Optional.of(new Claims(userId, tier, expiresAt));
    }

    private void requireSecret() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "atsidek.chat.context-token-secret (ATSIDEK_CHAT_CONTEXT_TOKEN_SECRET) "
                            + "nenurodyta - kontekstinio žetono neišduodu (fail closed)");
        }
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] writePayload(UUID userId, String tier, long exp) {
        ObjectNode node = MAPPER.createObjectNode();
        if (userId == null) {
            node.putNull("userId");
        } else {
            node.put("userId", userId.toString());
        }
        node.put("tier", tier);
        node.put("exp", exp);
        return node.toString().getBytes(StandardCharsets.UTF_8);
    }
}
