package eu.atsidek.bank;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Pagal nutylėjimą (production) banko ekranų nėra. */
@SpringBootTest
@AutoConfigureMockMvc
class BankDisabledTest {

    @Autowired MockMvc mvc;

    @Test
    void screensAreHiddenByDefault() throws Exception {
        for (String p : new String[]{"/importas", "/suvestine", "/taisykles", "/nustatymai", "/importai/abc"}) {
            mvc.perform(get(p)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/")).andExpect(status().isOk());
    }
}
