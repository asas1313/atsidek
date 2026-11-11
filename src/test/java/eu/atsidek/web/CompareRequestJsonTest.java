package eu.atsidek.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import eu.atsidek.web.TaxApiController.CompareRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * Uses the REAL Spring Boot auto-configured {@code ObjectMapper} bean (via {@code @JsonTest}, which
 * loads {@code JacksonAutoConfiguration} plus {@code application.yml} - no DataSource/Testcontainers
 * needed) - the same mapper every {@code @RequestBody} controller actually uses in production -
 * instead of a hand-built one. This is a genuine proof that
 * {@code spring.jackson.deserialization.fail-on-unknown-properties=true} in {@code application.yml}
 * makes {@link CompareRequest} (and every other {@code @RequestBody} DTO) reject unknown JSON fields,
 * not just a simulation of Spring's old lenient default.
 */
@JsonTest
class CompareRequestJsonTest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    void parsesKnownFields() throws Exception {
        String json = "{\"ivRevenue\":30000,\"actualExpenses\":0,\"otherIncome\":100,"
                + "\"pensionAccumulation\":true,\"psdMinMonths\":6}";

        CompareRequest request = mapper.readValue(json, CompareRequest.class);

        assertThat(request.ivRevenue()).isEqualByComparingTo("30000");
        assertThat(request.actualExpenses()).isEqualByComparingTo("0");
        assertThat(request.otherIncome()).isEqualByComparingTo("100");
        assertThat(request.pensionAccumulation()).isTrue();
        assertThat(request.psdMinMonths()).isEqualTo(6);
    }

    @Test
    void parsesWithOptionalFieldsMissing() throws Exception {
        String json = "{\"ivRevenue\":1000}";

        CompareRequest request = mapper.readValue(json, CompareRequest.class);

        assertThat(request.ivRevenue()).isEqualByComparingTo("1000");
        assertThat(request.actualExpenses()).isNull();
        assertThat(request.otherIncome()).isNull();
        assertThat(request.pensionAccumulation()).isNull();
        assertThat(request.psdMinMonths()).isNull();
    }

    @Test
    void rejectsUnknownField() {
        String json = "{\"ivRevenue\":1000,\"bogusField\":123}";

        assertThatThrownBy(() -> mapper.readValue(json, CompareRequest.class))
                .isInstanceOf(UnrecognizedPropertyException.class);
    }
}
