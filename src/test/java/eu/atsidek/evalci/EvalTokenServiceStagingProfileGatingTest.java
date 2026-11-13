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
 * Explicit "staging" counterpart to {@link EvalTokenServiceProfileGatingTest}: today staging and
 * production run Spring Boot with NO {@code SPRING_PROFILES_ACTIVE} set at all (see
 * {@code deploy/deploy.sh}, {@code docker-compose.yml}, {@code .github/workflows/ci.yml}'s
 * {@code staging}/{@code production} jobs) - there is no Spring profile literally named
 * {@code "staging"} anywhere in {@code src/main}. This test activates one anyway, as a forward
 * guard: if a {@code "staging"} Spring profile is ever introduced later, {@code eval-ci}'s beans
 * and endpoint must still not leak into it.
 */
@SpringBootTest(properties = {
        "atsidek.api.key-hashes=62af8704764faf8ea82fc61ce9c4c3908b6cb97d463a634e9e587d7c885db0ef",
        "atsidek.chat.context-token-secret=test-context-token-secret"})
@ActiveProfiles("staging")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EvalTokenServiceStagingProfileGatingTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mvc;

    @Test
    void neitherBeanExistsUnderAStagingProfile() {
        assertThat(context.getBeanNamesForType(EvalTokenService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(EvalTokenController.class)).isEmpty();
    }

    @Test
    void endpointIsNotFoundUnderAStagingProfileEvenWithACorrectApiKey() throws Exception {
        mvc.perform(post("/api/v1/eval/token").header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"testUserKey\":\"premium\"}"))
                .andExpect(status().isNotFound());
    }
}
