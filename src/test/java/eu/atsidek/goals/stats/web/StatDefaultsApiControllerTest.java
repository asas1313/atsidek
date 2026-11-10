package eu.atsidek.goals.stats.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.atsidek.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** {@code /api/v1/goals/stats/defaults} – uždarytas {@code ApiKeyFilter} per {@code /api/**} prefiksą. */
@SpringBootTest(properties = "atsidek.api.key-hashes=62af8704764faf8ea82fc61ce9c4c3908b6cb97d463a634e9e587d7c885db0ef")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StatDefaultsApiControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void rejectsMissingOrWrongKey() throws Exception {
        mvc.perform(get("/api/v1/goals/stats/defaults")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/goals/stats/defaults").header("X-API-Key", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsKeyAndReturnsDefaults() throws Exception {
        mvc.perform(get("/api/v1/goals/stats/defaults").header("X-API-Key", "test-key")
                        .param("members", "4").param("workers", "2").param("goalType", "CAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.income.value").value(3200.00))
                .andExpect(jsonPath("$.typicalAmount.value").value(25000.00));
    }

    @Test
    void acceptsBearerKey() throws Exception {
        mvc.perform(get("/api/v1/goals/stats/defaults").header("Authorization", "Bearer test-key"))
                .andExpect(status().isOk());
    }
}
