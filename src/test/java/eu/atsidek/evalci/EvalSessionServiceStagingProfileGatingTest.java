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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Explicit "staging" counterpart to {@link EvalSessionServiceProfileGatingTest}, mirroring
 * {@link EvalTokenServiceStagingProfileGatingTest}: today staging/production run with no Spring
 * profile named {@code "staging"} at all - this test activates one anyway as a forward guard, so
 * {@code eval-ci}'s session-minting beans never leak into a future profile of that name either.
 */
@SpringBootTest(properties = {
        "atsidek.api.key-hashes=62af8704764faf8ea82fc61ce9c4c3908b6cb97d463a634e9e587d7c885db0ef",
        "atsidek.chat.context-token-secret=test-context-token-secret"})
@ActiveProfiles("staging")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EvalSessionServiceStagingProfileGatingTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mvc;

    @Test
    void neitherBeanExistsUnderAStagingProfile() {
        assertThat(context.getBeanNamesForType(EvalSessionService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(EvalSessionController.class)).isEmpty();
    }

    @Test
    void endpointIsNotFoundUnderAStagingProfileEvenWithACorrectApiKey() throws Exception {
        mvc.perform(post("/api/v1/eval/session").header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"testUserKey\":\"premium\"}"))
                .andExpect(status().isNotFound());
    }
}
