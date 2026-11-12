package eu.atsidek.goals.plan.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import eu.atsidek.auth.ContextTokenService;
import eu.atsidek.auth.CurrentAppUser;
import eu.atsidek.goals.plan.SavingsPlanService;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pure (be Spring HTTP sluoksnio/MockMvc/DB) {@link SavingsPlanApiController} RANKA parašyto JSON
 * skaitymo ({@link SavingsPlanApiController#plan}) testai - {@code SavingsPlanService} nemockinamas
 * reikšmingai, nes šios klaidos atsiranda PRIEŠ jo kvietimą (parsinimo etape). Pilnas HTTP sluoksnio
 * (su tikru {@code DispatcherServlet}/{@code consumes} patikra, kuri reikalauja Spring MVC) testas -
 * {@code SavingsPlanApiControllerTest} (reikalauja Docker/Testcontainers).
 */
class SavingsPlanApiControllerUnitTest {

    private final SavingsPlanApiController controller = new SavingsPlanApiController(
            mock(SavingsPlanService.class), new CurrentAppUser(),
            new ContextTokenService("unit-test-secret", Clock.systemUTC()));

    @Test
    void trailingGarbageAfterValidJsonIsRejectedWith400() {
        String json = """
                {"goal":{"type":"CAR","amount":1200,"mode":"YEARS","years":5},
                 "net_monthly_income":3100} garbage
                """;

        assertThatThrownBy(() -> controller.plan(json, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> org.assertj.core.api.Assertions
                        .assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void unknownTopLevelFieldIsStillRejectedWith400() {
        String json = """
                {"goal":{"type":"CAR","amount":1200,"mode":"YEARS","years":5},
                 "net_monthly_income":3100,"unexpected_field":true}
                """;

        assertThatThrownBy(() -> controller.plan(json, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> org.assertj.core.api.Assertions
                        .assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void forgedContextTokenIsRejectedWith401EvenBeforeParsingBody() {
        String genuine = new ContextTokenService("unit-test-secret", Clock.systemUTC())
                .issue(java.util.UUID.randomUUID(), "PREMIUM");
        // Pakeičiam PIRMĄ parašo simbolį (ne paskutinį - nepilnos base64 grupės kraštinis simbolis
        // gali iškoduoti į tą pačią baito reikšmę, žr. SavingsPlanApiControllerTest pastabą).
        int dot = genuine.indexOf('.');
        char first = genuine.charAt(dot + 1);
        char replacement = first == 'A' ? 'Z' : 'A';
        String forged = genuine.substring(0, dot + 1) + replacement + genuine.substring(dot + 2);

        assertThatThrownBy(() -> controller.plan("{}", forged))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> org.assertj.core.api.Assertions
                        .assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
    }
}
