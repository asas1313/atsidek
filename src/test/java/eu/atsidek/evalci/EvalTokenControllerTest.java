package eu.atsidek.evalci;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.atsidek.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/v1/eval/token} - mounted under {@code /api/**}, so it inherits
 * {@code ApiKeyFilter} (see {@code TaxApiControllerTest} for the same hash/key pairing - "test-key"
 * / its SHA-256 hex, no new secret invented here). {@code @ActiveProfiles("eval-ci")} is required
 * for the controller bean to exist at all - see {@link EvalTokenServiceProfileGatingTest} for the
 * negative case.
 */
@SpringBootTest(properties = {
        "atsidek.api.key-hashes=62af8704764faf8ea82fc61ce9c4c3908b6cb97d463a634e9e587d7c885db0ef",
        "atsidek.chat.context-token-secret=test-context-token-secret"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("eval-ci")
class EvalTokenControllerTest {

    @Autowired
    MockMvc mvc;

    private org.springframework.test.web.servlet.ResultActions mint(String testUserKey) throws Exception {
        return mvc.perform(post("/api/v1/eval/token").header("X-API-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"testUserKey\":\"" + testUserKey + "\"}"));
    }

    @Test
    void rejectsMissingOrWrongApiKey() throws Exception {
        mvc.perform(post("/api/v1/eval/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"testUserKey\":\"free\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/eval/token").header("X-API-Key", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"testUserKey\":\"free\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mintsAPremiumToken() throws Exception {
        mint("premium")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.derivedTier").value("PREMIUM"))
                .andExpect(jsonPath("$.contextToken").isNotEmpty())
                .andExpect(jsonPath("$.userId").isNotEmpty());
    }

    @Test
    void mintsAFreeToken() throws Exception {
        mint("free")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.derivedTier").value("FREE"));
    }

    @Test
    void rejectsAnUnknownTestUserKeyWith400() throws Exception {
        mint("premium-plus").andExpect(status().isBadRequest());
    }
}
