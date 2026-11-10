package eu.atsidek.goals.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.atsidek.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** F3: viešas puslapis, be API rakto, be prisijungimo (žr. SecurityConfig permitAll). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GoalsPageControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void pageRendersWithFormAndResultElements() throws Exception {
        mvc.perform(get("/taupymo-tikslai"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Kiek reikia atsidėti?")))
                .andExpect(content().string(containsString("id=\"goalType-child\"")))
                .andExpect(content().string(containsString("id=\"mode-childage\"")))
                .andExpect(content().string(containsString("id=\"result-number\"")))
                .andExpect(content().string(containsString("/goals/calculate")))
                .andExpect(content().string(containsString("/goals/stats/defaults")))
                .andExpect(content().string(containsString("ne finansinė konsultacija")));
    }

    @Test
    void everyFieldHasARealLabel() throws Exception {
        mvc.perform(get("/taupymo-tikslai"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<label for=\"amount\">")))
                .andExpect(content().string(containsString("<label for=\"income\">")))
                .andExpect(content().string(containsString("<label for=\"expenses\">")))
                .andExpect(content().string(containsString("<label for=\"annualReturn\">")))
                .andExpect(content().string(containsString("<label for=\"annualInflation\">")))
                .andExpect(content().string(containsString("<label for=\"members\">")))
                .andExpect(content().string(containsString("<label for=\"workers\">")));
    }

    @Test
    void navOnOtherPagesLinksToNewGoalsPageAndGroupsIvMokesciai() throws Exception {
        mvc.perform(get("/skaiciuokle"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/taupymo-tikslai\"")))
                .andExpect(content().string(containsString("Individualios veiklos mokesčiai")))
                .andExpect(content().string(containsString("href=\"/skaiciuokle\"")));
    }
}
