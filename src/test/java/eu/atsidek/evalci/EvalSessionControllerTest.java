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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code POST /api/v1/eval/session} - mounted under {@code /api/**}, so it inherits
 * {@code ApiKeyFilter} just like {@link EvalTokenControllerTest}. The real point of this endpoint
 * (B7, {@code run_eval.py --via-endpoint}) is that the session it mints actually authenticates a
 * LATER, independent request - verified here by reusing the same {@link MockHttpSession} for a
 * follow-up {@code /api/v1/savings/plan} call and checking the tier it sees matches, exactly what
 * the owner asked the Python-side preflight to also verify (never trust the mint response alone).
 */
@SpringBootTest(properties = {
        "atsidek.api.key-hashes=62af8704764faf8ea82fc61ce9c4c3908b6cb97d463a634e9e587d7c885db0ef",
        "atsidek.chat.context-token-secret=test-context-token-secret"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("eval-ci")
class EvalSessionControllerTest {

    private static final String SAVINGS_PLAN_BODY = """
            {"goal":{"type":"CAR","amount":1200,"mode":"WHEN","monthly_contribution":100},
             "net_monthly_income":3100,"monthly_expenses":1000}
            """;

    @Autowired
    MockMvc mvc;

    private ResultActions establish(String testUserKey, MockHttpSession session) throws Exception {
        return mvc.perform(post("/api/v1/eval/session").header("X-API-Key", "test-key").session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"testUserKey\":\"" + testUserKey + "\"}"));
    }

    @Test
    void rejectsMissingOrWrongApiKey() throws Exception {
        mvc.perform(post("/api/v1/eval/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"testUserKey\":\"free\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/eval/session").header("X-API-Key", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"testUserKey\":\"free\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnUnknownTestUserKeyWith400() throws Exception {
        establish("premium-plus", new MockHttpSession()).andExpect(status().isBadRequest());
    }

    @Test
    void mintsASessionReportingThePremiumTier() throws Exception {
        establish("premium", new MockHttpSession())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.derivedTier").value("PREMIUM"))
                .andExpect(jsonPath("$.userId").isNotEmpty());
    }

    @Test
    void mintsASessionReportingTheFreeTier() throws Exception {
        establish("free", new MockHttpSession())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.derivedTier").value("FREE"));
    }

    @Test
    void aMintedPremiumSessionActuallyAuthenticatesALaterSavingsPlanCall() throws Exception {
        MockHttpSession session = new MockHttpSession();
        establish("premium", session).andExpect(status().isOk());

        mvc.perform(post("/api/v1/savings/plan").header("X-API-Key", "test-key").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(SAVINGS_PLAN_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("PREMIUM"))
                .andExpect(jsonPath("$.locked_features").isEmpty());
    }

    @Test
    void aMintedFreeSessionActuallyAuthenticatesALaterSavingsPlanCall() throws Exception {
        MockHttpSession session = new MockHttpSession();
        establish("free", session).andExpect(status().isOk());

        mvc.perform(post("/api/v1/savings/plan").header("X-API-Key", "test-key").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(SAVINGS_PLAN_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("FREE"))
                .andExpect(jsonPath("$.locked_features").isNotEmpty());
    }
}
