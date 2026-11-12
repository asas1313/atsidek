package eu.atsidek.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.atsidek.TestcontainersConfiguration;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Review round 1 SHOULD #1: {@code /chat/send} must not block the Tomcat worker thread forever
 * when the Python {@code assistant} backend is slow/unreachable - it is {@code permitAll()}
 * (anon-reachable with just a CSRF token), so an unbounded wait is a thread-pool-exhaustion DoS
 * path. Uses the REAL {@link RestClientAssistantClient} (no {@code @MockitoBean} override, unlike
 * {@code ChatControllerTest}) pointed at a socket that accepts the TCP connection but never writes
 * a response - exactly the "slow backend" scenario, not a "connection refused" one.
 */
@SpringBootTest(properties = {"atsidek.chat.context-token-secret=test-context-token-secret",
        "atsidek.assistant.timeout-ms=200"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ChatSendAssistantTimeoutTest {

    private static ServerSocket hungBackend;

    @BeforeAll
    static void startHungBackend() throws IOException {
        // Deliberately never accept() a connection from this socket - the client's TCP handshake
        // completes (kernel backlog), but no response ever arrives, simulating a slow/hung Python
        // assistant backend without racing an actual slow service in the test.
        hungBackend = new ServerSocket(0);
    }

    @AfterAll
    static void stopHungBackend() throws IOException {
        hungBackend.close();
    }

    @DynamicPropertySource
    static void assistantBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("atsidek.assistant.base-url", () -> "http://localhost:" + hungBackend.getLocalPort());
    }

    @Autowired
    MockMvc mvc;

    @Test
    void hungAssistantBackendFailsCleanlyInsteadOfHangingTheWorkerThreadForever() throws Exception {
        var result = mvc.perform(post("/chat/send").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Labas\"}"))
                .andExpect(status().isBadGateway())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("Exception", "eu.atsidek", "java.net", "\tat ");
    }
}
