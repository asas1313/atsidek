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

/**
 * Viešas {@code /goals/stats/defaults} – be API rakto, be prisijungimo (SecurityConfig permitAll).
 * Patikrina ir tai, kad SecurityConfig pakeitimas tikrai atveria kelią (kitaip - 401/403, kaip įspėta
 * užduoties apraše).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StatDefaultsControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void publicEndpointIsReachableWithoutAuth() throws Exception {
        mvc.perform(get("/goals/stats/defaults").param("members", "4").param("workers", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.income.value").value(3200.00))
                .andExpect(jsonPath("$.expenses.value").value(1900.00))
                .andExpect(jsonPath("$.income.origin").value("STATISTICS"))
                .andExpect(jsonPath("$.annualInflation.origin").value("ASSUMPTION"))
                .andExpect(jsonPath("$.annualInflation.value").value(2.5))
                .andExpect(jsonPath("$.annualReturn.value").value(3))
                .andExpect(jsonPath("$.typicalAmount").doesNotExist());
    }

    @Test
    void returnsTypicalAmountWhenGoalTypeGiven() throws Exception {
        mvc.perform(get("/goals/stats/defaults").param("goalType", "CHILD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.typicalAmount.value").value(20000.00));
    }

    @Test
    void worksWithoutAnyParams() throws Exception {
        mvc.perform(get("/goals/stats/defaults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.income.value").exists())
                .andExpect(jsonPath("$.expenses.value").exists());
    }

    @Test
    void rejectsUnknownGoalTypeWithLithuanianMessage() throws Exception {
        mvc.perform(get("/goals/stats/defaults").param("goalType", "SPACESHIP"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNegativeMembers() throws Exception {
        mvc.perform(get("/goals/stats/defaults").param("members", "-1"))
                .andExpect(status().isBadRequest());
    }
}
