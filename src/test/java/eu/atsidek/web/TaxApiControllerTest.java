package eu.atsidek.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.atsidek.TestcontainersConfiguration;
import eu.atsidek.auth.ContextTokenService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "atsidek.api.key-hashes=62af8704764faf8ea82fc61ce9c4c3908b6cb97d463a634e9e587d7c885db0ef",
        "atsidek.chat.context-token-secret=test-context-token-secret"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TaxApiControllerTest {

    private static final String TOKEN_SECRET = "test-context-token-secret";

    @Autowired
    MockMvc mvc;
    @Autowired
    Clock clock;

    private org.springframework.test.web.servlet.ResultActions compare(String json) throws Exception {
        return mvc.perform(post("/api/v1/compare").header("X-API-Key", "test-key").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void rejectsMissingOrWrongKey() throws Exception {
        String body = "{\"ivRevenue\":30000}";
        mvc.perform(post("/api/v1/compare").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/compare").header("X-API-Key", "wrong")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsBearerKey() throws Exception {
        mvc.perform(post("/api/v1/compare").header("Authorization", "Bearer test-key")
                .contentType(MediaType.APPLICATION_JSON).content("{\"ivRevenue\":30000}"))
                .andExpect(status().isOk());
    }

    @Test
    void returnsBothMethodsAndBetter() throws Exception {
        compare("{\"ivRevenue\":30000,\"actualExpenses\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flat.total").value(4875.50))
                .andExpect(jsonPath("$.actual.total").exists())
                .andExpect(jsonPath("$.better.total").exists());
    }

    @Test
    void returnsJavaComputedNetMonthlyIncome() throws Exception {
        // (30000 - 4875.50) / 12 = 2093.71 (B1, owner sprendimas 5 - žr. TaxEngineTest)
        compare("{\"ivRevenue\":30000,\"actualExpenses\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flat.netMonthlyIncome").value(2093.71));
    }

    @Test
    void rejectsNegativeAmount() throws Exception {
        compare("{\"ivRevenue\":30000,\"actualExpenses\":-150}").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingRevenue() throws Exception {
        compare("{}").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsPsdMonthsOutOfRange() throws Exception {
        compare("{\"ivRevenue\":1000,\"psdMinMonths\":13}").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownField() throws Exception {
        compare("{\"ivRevenue\":1000,\"bogusField\":123}").andExpect(status().isBadRequest());
    }

    @Test
    void missingContextTokenBehavesExactlyAsBeforeAnonymousApiKeyGatedOnly() throws Exception {
        // Šiandieniniai Python /asistentas kvietimai (AtsidekApi.compare()) NIEKADA siunčia šį
        // antraštę - šis testas yra tiesioginis "zero behaviour change" regresijos įrodymas.
        compare("{\"ivRevenue\":30000,\"actualExpenses\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flat.total").value(4875.50));
    }

    @Test
    void validContextTokenIsAccepted() throws Exception {
        String token = new ContextTokenService(TOKEN_SECRET, clock).issue(UUID.randomUUID(), "PREMIUM");
        mvc.perform(post("/api/v1/compare").header("X-API-Key", "test-key")
                        .header("X-Atsidek-Context-Token", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ivRevenue\":30000}"))
                .andExpect(status().isOk());
    }

    @Test
    void forgedContextTokenIsRejected() throws Exception {
        String genuine = new ContextTokenService(TOKEN_SECRET, clock).issue(UUID.randomUUID(), "PREMIUM");
        String forged = forgeSignature(genuine);
        mvc.perform(post("/api/v1/compare").header("X-API-Key", "test-key")
                        .header("X-Atsidek-Context-Token", forged)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ivRevenue\":30000}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredContextTokenIsRejected() throws Exception {
        Clock past = Clock.fixed(clock.instant().minus(Duration.ofMinutes(10)), clock.getZone());
        String expired = new ContextTokenService(TOKEN_SECRET, past).issue(UUID.randomUUID(), "PREMIUM");
        mvc.perform(post("/api/v1/compare").header("X-API-Key", "test-key")
                        .header("X-Atsidek-Context-Token", expired)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ivRevenue\":30000}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Pakeičia PIRMĄ parašo dalies simbolį (ne paskutinį - jis gali kirpti nepilną base64 grupę,
     * kur kelios reikšmės iškoduojamos į tą patį baitą, žr. review'ą) - tai GARANTUOTAI pakeičia
     * dekoduotą parašą, kadangi jis yra pilnos 4 simbolių/3 baitų grupės dalis.
     */
    private static String forgeSignature(String token) {
        int dot = token.indexOf('.');
        char first = token.charAt(dot + 1);
        char replacement = first == 'A' ? 'Z' : 'A';
        return token.substring(0, dot + 1) + replacement + token.substring(dot + 2);
    }
}
