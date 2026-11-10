package eu.atsidek.goals.web;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** PLAN.md K3: viešas, be API rakto, grynas JSON (ne htmx fragmentas). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GoalsControllerTest {

    @Autowired
    MockMvc mvc;

    private ResultActions calculate(String json) throws Exception {
        return mvc.perform(post("/goals/calculate").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /**
     * {@link org.springframework.web.server.ResponseStatusException} žinutė (lietuviškas klaidos pranešimas)
     * nepatenka į MockMvc atsakymo turinį (tuščias {@code sendError} korpusas), todėl tikrinama per
     * {@link MvcResult#getResolvedException()}, ne per response body.
     */
    private void assertBadRequestWithMessage(String json, String expectedMessageFragment) throws Exception {
        MvcResult result = calculate(json).andExpect(status().isBadRequest()).andReturn();
        Throwable resolved = result.getResolvedException();
        assertTrue(resolved != null && resolved.getMessage() != null
                        && resolved.getMessage().contains(expectedMessageFragment),
                "tikėtasi klaidos žinutėje: " + expectedMessageFragment + ", gauta: "
                        + (resolved == null ? null : resolved.getMessage()));
    }

    @Test
    void yearsModeReturnsCalculatedResult() throws Exception {
        calculate("""
                {"goalType":"OTHER","mode":"YEARS","amount":12000,"years":5,
                 "household":{"income":2000,"expenses":1500},
                 "assumptions":{"annualReturn":0,"annualInflation":0}}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthly").value(200.00))
                .andExpect(jsonPath("$.months").value(60))
                .andExpect(jsonPath("$.verdict").value("TIGHT"))
                .andExpect(jsonPath("$.reachable").value(true))
                .andExpect(jsonPath("$.resolvedInputs.annualReturn.origin").value("USER"));
    }

    @Test
    void missingAmountIsRejectedWithLithuanianMessage() throws Exception {
        assertBadRequestWithMessage("{\"goalType\":\"OTHER\",\"mode\":\"YEARS\",\"years\":5}", "amount privalomas");
    }

    @Test
    void missingYearsForYearsModeIsRejected() throws Exception {
        assertBadRequestWithMessage("{\"goalType\":\"OTHER\",\"mode\":\"YEARS\",\"amount\":1000}", "years privalomas");
    }

    @Test
    void childTargetAgeNotGreaterThanCurrentIsRejected() throws Exception {
        assertBadRequestWithMessage("{\"goalType\":\"CHILD\",\"mode\":\"CHILD_AGE\",\"amount\":1000,"
                + "\"childCurrentAge\":10,\"childTargetAge\":5}", "childTargetAge turi būti didesnis");
    }

    @Test
    void negativeAmountIsRejected() throws Exception {
        assertBadRequestWithMessage("{\"goalType\":\"OTHER\",\"mode\":\"YEARS\",\"amount\":-100,\"years\":1}",
                "amount negali būti neigiamas");
    }

    @Test
    void whenModeWithoutMonthlyContributionIsRejected() throws Exception {
        assertBadRequestWithMessage("{\"goalType\":\"OTHER\",\"mode\":\"WHEN\",\"amount\":1000}",
                "monthlyContribution privalomas");
    }

    @Test
    void noApiKeyRequiredOnPublicPath() throws Exception {
        calculate("{\"goalType\":\"OTHER\",\"mode\":\"YEARS\",\"amount\":1000,\"years\":1}")
                .andExpect(status().isOk());
    }
}
