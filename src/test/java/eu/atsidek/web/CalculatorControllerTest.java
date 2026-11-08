package eu.atsidek.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CalculatorControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void indexRendersWithResult() throws Exception {
        mvc.perform(get("/skaiciuokle"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Iš viso mokesčių")));
    }

    @Test
    void calculateReturnsFragmentWithLithuanianNumbers() throws Exception {
        mvc.perform(post("/calculate").header("HX-Request", "true").param("revenue", "30 000").param("method", "FLAT_30"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("4 875,50 €")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<html"))));
    }

    @Test
    void privacyPageRenders() throws Exception {
        mvc.perform(get("/privatumas"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Privatumo politika")));
    }

    @Test
    void garbageInputDoesNotBreak() throws Exception {
        mvc.perform(post("/calculate").param("revenue", "abc").param("otherIncome", "-5"))
                .andExpect(status().isOk());
    }

    @Test
    void seoFilesAndCanonicalPresent() throws Exception {
        mvc.perform(get("/robots.txt")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sitemap: https://atsidek.eu/sitemap.xml")));
        mvc.perform(get("/sitemap.xml")).andExpect(status().isOk());
        mvc.perform(get("/skaiciuokle")).andExpect(content().string(org.hamcrest.Matchers.containsString("rel=\"canonical\" href=\"https://atsidek.eu/skaiciuokle\"")));
        // Pagrindinį puslapį „/“ rodo Java (meniu), o rezultatą į #result įdeda AI asistento servisas
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("hx-post=\"/asistentas\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/suvestine\"")));
    }

    @Test
    void articleRenders() throws Exception {
        mvc.perform(get("/straipsniai/30-proc-ar-faktines-islaidos")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("4 876,31 €")));
    }
}
