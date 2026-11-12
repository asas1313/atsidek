package eu.atsidek.chat;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link AssistantClient} implementacija per Spring {@code RestClient} (jau yra su
 * {@code spring-boot-starter-web} - NAUJOS Maven priklausomybės nereikia, žr.
 * PLAN-taupymas-agentas.B.plan.md B1). Vidinis adresas - Python {@code assistant} servisas
 * (docker-compose serviso pavadinimas/prievadas), NE viešas internetas.
 */
@Component
public class RestClientAssistantClient implements AssistantClient {

    private static final String CONTEXT_TOKEN_HEADER = "X-Atsidek-Context-Token";

    private final RestClient restClient;

    public RestClientAssistantClient(@Value("${atsidek.assistant.base-url:http://assistant:8000}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public Object chat(String contextToken, String message) {
        return restClient.post()
                .uri("/internal/chat")
                .header(CONTEXT_TOKEN_HEADER, contextToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", message))
                .retrieve()
                .body(Object.class);
    }
}
