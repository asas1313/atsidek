package eu.atsidek.goals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * Uses the REAL Spring Boot auto-configured {@code ObjectMapper} bean (via {@code @JsonTest}, which
 * loads {@code JacksonAutoConfiguration} plus {@code application.yml} - no DataSource/Testcontainers
 * needed) to prove that {@code spring.jackson.deserialization.fail-on-unknown-properties=true} also
 * makes {@link GoalInput} - shared by {@code GoalsController}, {@code GoalsApiController} and
 * {@code ManoTikslasController} - reject unknown JSON fields instead of silently dropping them.
 */
@JsonTest
class GoalInputJsonTest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    void parsesKnownFields() throws Exception {
        String json = "{\"goalType\":\"OTHER\",\"mode\":\"YEARS\",\"amount\":12000,\"years\":5,"
                + "\"household\":{\"income\":2000,\"expenses\":1500},"
                + "\"assumptions\":{\"annualReturn\":0,\"annualInflation\":0}}";

        GoalInput input = mapper.readValue(json, GoalInput.class);

        assertThat(input.goalType()).isEqualTo(GoalType.OTHER);
        assertThat(input.mode()).isEqualTo(GoalMode.YEARS);
        assertThat(input.amount()).isEqualByComparingTo("12000");
        assertThat(input.years()).isEqualTo(5);
    }

    @Test
    void rejectsUnknownField() {
        String json = "{\"goalType\":\"OTHER\",\"mode\":\"YEARS\",\"amount\":12000,\"years\":5,"
                + "\"bogusField\":123}";

        assertThatThrownBy(() -> mapper.readValue(json, GoalInput.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }
}
