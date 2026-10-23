package lt.atsidek.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CalculatorControllerTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("atsidek.signups-file", () -> "target/test-signups.csv");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void indexRendersWithResult() throws Exception {
        mvc.perform(get("/"))
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
    void signupIsRateLimited() throws Exception {
        for (int i = 0; i < RateLimiter.MAX; i++) {
            mvc.perform(post("/signup").with(r -> { r.setRemoteAddr("10.9.9.9"); return r; }).param("email", "bad"));
        }
        mvc.perform(post("/signup").with(r -> { r.setRemoteAddr("10.9.9.9"); return r; }).param("email", "a@b.lt").param("consent", "true"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Per daug")));
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
    void signupValidatesEmailAndConsent() throws Exception {
        mvc.perform(post("/signup").param("email", "bad").param("consent", "true"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Patikrink")));
        mvc.perform(post("/signup").param("email", "a@b.lt"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("sutikimas")));
        mvc.perform(post("/signup").param("email", "a@b.lt").param("consent", "true"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ačiū")));
    }
}
