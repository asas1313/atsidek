package eu.atsidek.evalci;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/eval/session} - TIK B6/B7 gyvo agento eval'o CI, analogiškai
 * {@link EvalTokenController} (žr. jos ir {@link EvalSessionService} javadoc dėl pilnos saugumo
 * argumentacijos ir skirtumo nuo {@code /api/v1/eval/token}). Kelias sąmoningai {@code /api/v1/**}
 * - automatiškai patenka po {@code SecurityConfig}'o {@code /api/**} {@code permitAll()} +
 * {@code ApiKeyFilter} apsauga, be jokio naujo {@code SecurityConfig} pakeitimo. Atsakymas nešasi
 * {@code Set-Cookie} sesijos slapuką - skambinantysis (žr. {@code run_eval.py --via-endpoint})
 * turi jį pakartotinai naudoti kiekvienam sekančiam {@code /chat/send} kvietimui per tą pačią
 * HTTP klientą/cookie jar.
 */
@RestController
@Profile("eval-ci")
public class EvalSessionController {

    private final EvalSessionService evalSessionService;

    public EvalSessionController(EvalSessionService evalSessionService) {
        this.evalSessionService = evalSessionService;
    }

    public record SessionRequest(String testUserKey) {
    }

    public record SessionResponse(String derivedTier, UUID userId) {
    }

    @PostMapping("/api/v1/eval/session")
    public SessionResponse establish(@RequestBody SessionRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        EvalSessionService.Session session =
                evalSessionService.establish(request.testUserKey(), httpRequest, httpResponse);
        return new SessionResponse(session.derivedTier(), session.userId());
    }
}
