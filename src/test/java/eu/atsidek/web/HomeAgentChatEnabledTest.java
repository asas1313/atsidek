package eu.atsidek.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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

/**
 * B6.6: {@code atsidek.chat.agent-enabled=true} (vėliavos ON kontekstas, atskira Spring konteksto
 * instancija nuo {@link CalculatorControllerTest}'o numatytosios/false) - naujas pokalbio UI
 * pagrindiniame puslapyje, senas /asistentas forma NERODOMA.
 */
@SpringBootTest(properties = "atsidek.chat.agent-enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class HomeAgentChatEnabledTest {

    @Autowired
    MockMvc mvc;

    @Test
    void homeShowsChatUiAndNotTheOldForm() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"chat-form\"")))
                .andExpect(content().string(containsString("/chat/send")))
                .andExpect(content().string(not(containsString("hx-post=\"/asistentas\""))));
    }

    @Test
    void goalsPageShowsLinkToTheChat() throws Exception {
        mvc.perform(get("/taupymo-tikslai"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Klausti AI asistento")));
    }
}
