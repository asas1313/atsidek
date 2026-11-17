package eu.atsidek.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.atsidek.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * B6.6 end-to-end HTTP smoke test (žr. PLAN-taupymas-agentas.md B6.6 AC 4): anoniminė žinutė per
 * TIKRĄ HTTP/security/sesijos sluoksnį į {@code POST /chat/send} gauna atgal taupymo šablono
 * atsakymą - ne Playwright naršyklės testas (projektas jo neturi, žr. Makefile {@code e2e} TODO),
 * o HTTP lygmens patikra, kad visas Java sujungimas (ChatController → AssistantClient → JSON)
 * teisingai perduoda realistišką, response_builder.py stiliaus šablono atsakymą iki naršyklės -
 * AssistantClient yra vienintelis tinklo kraštas, pakeistas mock'u (kaip ir ChatControllerTest),
 * nes tikras Claude/Python kelias reikalauja ANTHROPIC_API_KEY ir yra padengtas atskirai Python
 * pusės test_internal_chat_agent.py (B6.5).
 */
@SpringBootTest(properties = "atsidek.chat.context-token-secret=test-context-token-secret")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ChatSendSavingsTemplateSmokeTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AssistantClient assistantClient;

    @Test
    void anonymousSavingsMessageGetsBackTheSavingsTemplateReply() throws Exception {
        String savingsTemplateReply = "Laisvų pinigų per mėnesį lieka 400,00 €.\n\n"
                + "Tikslui siūloma atsidėti 208,33 € per mėnesį (52,1% laisvų pinigų).\n\n"
                + "Tai orientacinis įvertis planavimui, ne finansinė konsultacija.\n\n"
                + "Jūsų pajamos svyruoja – jei prisijungsite, pamatysite, kiek atsidėti stipresniais ir silpnesniais mėnesiais.";
        when(assistantClient.chat(anyString(), anyString(), any(), any(), any()))
                .thenReturn(Map.of("reply", savingsTemplateReply));

        String response = mvc.perform(post("/chat/send").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Noriu sutaupyti 5000 eurų per 2 metus automobiliui, mano grynosios "
                                + "mėnesio pajamos 1200 eurų, išlaidos 800 eurų.\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).contains("Laisvų pinigų per mėnesį lieka")
                .contains("Tikslui siūloma atsidėti")
                .contains("ne finansinė konsultacija")
                .contains("jei prisijungsite");
    }
}
