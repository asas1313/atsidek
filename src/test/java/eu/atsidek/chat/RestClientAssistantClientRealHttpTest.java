package eu.atsidek.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import eu.atsidek.TestcontainersConfiguration;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
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
 * Regression test for a REAL bug found via the first live {@code --via-endpoint} CI run (B6.5,
 * 2026-11-17): {@link RestClientAssistantClient}'s JDK {@code HttpClient} defaulted to an HTTP/2
 * cleartext-upgrade attempt on every request. The real Python {@code assistant} service (uvicorn/
 * h11, HTTP/1.1 only) rejected that upgrade ("Unsupported upgrade request"), and the retry that
 * followed lost the request body entirely - Python/FastAPI saw an EMPTY body and returned 422
 * ("Field required"), which {@link RestClientAssistantClient}'s own catch turned into an opaque
 * 502 to the browser. Fixed by pinning the JDK {@code HttpClient} to HTTP/1.1.
 * <p>
 * {@code ChatControllerTest}'s {@code @MockitoBean AssistantClient} never exercises real HTTP at
 * all, so this bug was completely invisible to it - this test uses a REAL {@code HttpServer}
 * standing in for the Python service (same spirit as {@code ChatSendAssistantTimeoutTest}'s hung
 * socket, but one that actually answers) to prove the request body genuinely arrives intact over
 * the wire, closing the "fake Python service" integration-test gap that mock left open.
 */
@SpringBootTest(properties = {"atsidek.chat.context-token-secret=test-context-token-secret"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RestClientAssistantClientRealHttpTest {

    private static HttpServer fakePythonService;
    private static final AtomicReference<String> receivedBody = new AtomicReference<>();
    private static final AtomicReference<String> receivedContextToken = new AtomicReference<>();

    @BeforeAll
    static void startFakePythonService() throws IOException {
        fakePythonService = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakePythonService.createContext("/internal/chat", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedContextToken.set(exchange.getRequestHeaders().getFirst("X-Atsidek-Context-Token"));
            byte[] response = "{\"reply\":\"ack\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        fakePythonService.start();
    }

    @AfterAll
    static void stopFakePythonService() {
        fakePythonService.stop(0);
    }

    @DynamicPropertySource
    static void assistantBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("atsidek.assistant.base-url",
                () -> "http://127.0.0.1:" + fakePythonService.getAddress().getPort());
    }

    @Autowired
    MockMvc mvc;

    @Test
    void theRequestBodyAndContextTokenActuallyArriveIntactOverRealHttp() throws Exception {
        mvc.perform(post("/chat/send").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Labas, kiek mokesčių?\"}"))
                .andExpect(status().isOk());

        // If the HTTP/2-upgrade bug ever came back, this would be null/blank - uvicorn's real
        // rejection of the upgrade attempt loses the body before FastAPI ever sees it.
        assertThat(receivedBody.get()).isNotNull().isNotBlank();
        assertThat(receivedBody.get()).contains("\"message\":\"Labas, kiek mokesčių?\"");
        assertThat(receivedBody.get()).contains("\"tier\":\"FREE\"");
        assertThat(receivedContextToken.get()).isNotNull().isNotBlank();
    }
}
