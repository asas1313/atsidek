package eu.atsidek.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Regression test for the B6 live-eval incident (2026-11-13, see
 * {@code docs/specs/taupymo-tikslai/B6-log.log}): the real GitHub Actions run set a genuine OS
 * environment variable named {@code ATSIDEK_CHAT_TOKEN_SECRET} (missing "CONTEXT") expecting it
 * to bind to {@code atsidek.chat.context-token-secret}. It silently did not - Spring's relaxed
 * env-var binding removes dashes but does NOT insert a word it isn't told about, so
 * {@code ATSIDEK_CHAT_TOKEN_SECRET} binds to the (nonexistent) property {@code atsidek.chat.
 * tokensecret}, never to {@code context-token-secret}. {@link ContextTokenService#issue} then
 * failed closed with a 500, exactly as the stacktrace shows.
 * <p>
 * Every existing test touching this property (e.g. {@code EvalTokenServiceTest},
 * {@code ChatControllerTest}) uses {@code @SpringBootTest(properties = "atsidek.chat.
 * context-token-secret=...")}, which sets the Spring property DIRECTLY via a test-only property
 * source - it never exercises {@code SystemEnvironmentPropertySource}'s relaxed binding at all,
 * so none of them could have caught a wrong ENV VAR NAME. This test is the one that would have:
 * it builds a real {@link SystemEnvironmentPropertySource} (the exact property-source type Spring
 * Boot uses for the real OS environment) and asserts the actually-documented/used env var name
 * binds - and that the old, broken name does not.
 */
class ContextTokenSecretEnvBindingTest {

    private static final String PROPERTY = "atsidek.chat.context-token-secret";

    private String bind(String envVarName, String envVarValue) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of(envVarName, envVarValue)));
        Binder binder = new Binder(ConfigurationPropertySources.get(env));
        return binder.bind(PROPERTY, Bindable.of(String.class)).orElse(null);
    }

    @Test
    void theDocumentedEnvVarNameActuallyBindsToTheContextTokenSecretProperty() {
        assertThat(bind("ATSIDEK_CHAT_CONTEXT_TOKEN_SECRET", "real-secret")).isEqualTo("real-secret");
    }

    @Test
    void theOldBrokenEnvVarNameFromTheB6IncidentDoesNotBind() {
        assertThat(bind("ATSIDEK_CHAT_TOKEN_SECRET", "real-secret")).isNull();
    }
}
