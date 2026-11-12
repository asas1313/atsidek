package eu.atsidek.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.atsidek.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Review round 1 NIT #6: when {@code atsidek.chat.context-token-secret} is blank/unconfigured,
 * {@link ContextTokenService#issue} throws {@link IllegalStateException} (fail closed, by
 * design), which {@link ChatController} does NOT catch. This must still surface as a clean 500 -
 * not a raw exception message/stack trace leak in the response body.
 */
@SpringBootTest(properties = "atsidek.chat.context-token-secret=")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ChatSendBlankSecretTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AssistantClient assistantClient;

    @Test
    void blankSecretSurfacesAsACleanFiveHundredNotAStackTraceLeak() throws Exception {
        var result = mvc.perform(post("/chat/send").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Labas\"}"))
                .andExpect(status().isInternalServerError())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("IllegalStateException", "eu.atsidek", "\tat ",
                "context-token-secret (ATSIDEK_CHAT_TOKEN_SECRET)");
    }
}
