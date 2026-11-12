package eu.atsidek.chat;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link AssistantClient} implementacija per Spring {@code RestClient} (jau yra su
 * {@code spring-boot-starter-web} - NAUJOS Maven priklausomybės nereikia, žr.
 * PLAN-taupymas-agentas.B.plan.md B1). Vidinis adresas - Python {@code assistant} servisas
 * (docker-compose serviso pavadinimas/prievadas), NE viešas internetas.
 * <p>
 * {@code /chat/send} yra {@code permitAll()} (review SHOULD #1) - be jungties/skaitymo limito
 * lėtas ar pakibęs Python servisas neribotai užblokuotų Tomcat worker gijas (thread-pool
 * exhaustion DoS). Abu limitai (jungties ir skaitymo) - ta pati reikšmė, kad būtų simetriška su
 * Python pusės {@code httpx.Client(timeout=10)} (žr. {@code assistant/app/calculator.py}).
 */
@Component
public class RestClientAssistantClient implements AssistantClient {

    private static final String CONTEXT_TOKEN_HEADER = "X-Atsidek-Context-Token";

    private final RestClient restClient;

    public RestClientAssistantClient(@Value("${atsidek.assistant.base-url:http://assistant:8000}") String baseUrl,
            @Value("${atsidek.assistant.timeout-ms:10000}") long timeoutMs) {
        Duration timeout = Duration.ofMillis(timeoutMs);
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect()
                .build(ClientHttpRequestFactorySettings.defaults().withTimeouts(timeout, timeout));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    /**
     * @throws ResponseStatusException 502, jei Python {@code assistant} servisas nepasiekiamas,
     *                                  pernelyg lėtas (žr. konstruktoriaus jungties/skaitymo
     *                                  limitą) ar grąžina klaidą - niekada perduoda žaliavinę
     *                                  {@link RestClientException} žinutę klientui (review SHOULD
     *                                  #1 - švarus, ribotas atsakymas, ne nutekėjęs stack trace).
     */
    @Override
    public Object chat(String contextToken, String message) {
        try {
            return restClient.post()
                    .uri("/internal/chat")
                    .header(CONTEXT_TOKEN_HEADER, contextToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("message", message))
                    .retrieve()
                    .body(Object.class);
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Nepavyko pasiekti AI asistento paslaugos.", e);
        }
    }
}
