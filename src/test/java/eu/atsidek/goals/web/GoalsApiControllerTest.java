package eu.atsidek.goals.web;

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
import org.springframework.test.web.servlet.ResultActions;

/** PLAN.md K2: tas pats skaičiavimas kaip {@code /goals/calculate}, tik po {@code /api/**} su API raktu. */
@SpringBootTest(properties = "atsidek.api.key-hashes=62af8704764faf8ea82fc61ce9c4c3908b6cb97d463a634e9e587d7c885db0ef")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GoalsApiControllerTest {

    private static final String REQUEST = """
            {"goalType":"OTHER","mode":"YEARS","amount":12000,"years":5,
             "household":{"income":2000,"expenses":1500},
             "assumptions":{"annualReturn":0,"annualInflation":0}}
            """;

    @Autowired
    MockMvc mvc;

    private ResultActions calculate(String json) throws Exception {
        return mvc.perform(post("/api/v1/goals/calculate").header("X-API-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void rejectsMissingOrWrongKey() throws Exception {
        mvc.perform(post("/api/v1/goals/calculate").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/goals/calculate").header("X-API-Key", "wrong")
                .contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsSameShapeAsPublicPath() throws Exception {
        calculate(REQUEST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthly").value(200.00))
                .andExpect(jsonPath("$.months").value(60))
                .andExpect(jsonPath("$.verdict").value("TIGHT"))
                .andExpect(jsonPath("$.reachable").value(true));
    }

    @Test
    void sharesValidationWithPublicPath() throws Exception {
        calculate("{\"goalType\":\"OTHER\",\"mode\":\"YEARS\",\"years\":5}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownField() throws Exception {
        calculate("{\"goalType\":\"OTHER\",\"mode\":\"YEARS\",\"amount\":12000,\"years\":5,"
                + "\"bogusField\":123}")
                .andExpect(status().isBadRequest());
    }
}
