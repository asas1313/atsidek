package eu.atsidek.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TaxApiControllerTest {

    @Autowired
    MockMvc mvc;

    private org.springframework.test.web.servlet.ResultActions compare(String json) throws Exception {
        return mvc.perform(post("/api/v1/compare").contentType(MediaType.APPLICATION_JSON).content(json));
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
}
