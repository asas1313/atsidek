package eu.atsidek.evalci;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/eval/token} - TIK B6 gyvo agento eval'o CI (žr.
 * {@code .github/workflows/eval-b6.yml} ir {@link EvalTokenService}'o javadoc dėl pilnos saugumo
 * argumentacijos). Kelias sąmoningai {@code /api/v1/**} - automatiškai patenka po
 * {@code SecurityConfig}'o {@code /api/**} {@code permitAll()} + {@code ApiKeyFilter} apsauga,
 * be jokio naujo {@code SecurityConfig} pakeitimo.
 */
@RestController
@Profile("eval-ci")
public class EvalTokenController {

    private final EvalTokenService evalTokenService;

    public EvalTokenController(EvalTokenService evalTokenService) {
        this.evalTokenService = evalTokenService;
    }

    public record MintRequest(String testUserKey) {
    }

    public record MintResponse(String contextToken, String derivedTier, UUID userId) {
    }

    @PostMapping("/api/v1/eval/token")
    public MintResponse mint(@RequestBody MintRequest request) {
        EvalTokenService.Minted minted = evalTokenService.mint(request.testUserKey());
        return new MintResponse(minted.contextToken(), minted.derivedTier(), minted.userId());
    }
}
