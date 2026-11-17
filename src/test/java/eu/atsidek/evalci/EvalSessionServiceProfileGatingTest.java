package eu.atsidek.evalci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.atsidek.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Negative case for {@code @Profile("eval-ci")} (see {@link EvalSessionService}/
 * {@link EvalSessionController} javadoc), the session-minting counterpart to
 * {@link EvalTokenServiceProfileGatingTest}: WITHOUT that profile active - the normal state for
 * every real environment - neither bean exists, and the endpoint is simply not there.
 */
@SpringBootTest(properties = {
        "atsidek.api.key-hashes=62af8704764faf8ea82fc61ce9c4c3908b6cb97d463a634e9e587d7c885db0ef",
        "atsidek.chat.context-token-secret=test-context-token-secret"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EvalSessionServiceProfileGatingTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mvc;

    @Test
    void neitherBeanExistsWithoutTheEvalCiProfile() {
        assertThat(context.getBeanNamesForType(EvalSessionService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(EvalSessionController.class)).isEmpty();
    }

    @Test
    void endpointIsNotFoundEvenWithACorrectApiKey() throws Exception {
        mvc.perform(post("/api/v1/eval/session").header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"testUserKey\":\"premium\"}"))
                .andExpect(status().isNotFound());
    }
}
