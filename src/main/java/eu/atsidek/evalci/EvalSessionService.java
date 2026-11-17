package eu.atsidek.evalci;

import eu.atsidek.auth.AppOidcUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

/**
 * TIK B6/B7 gyvo agento eval'o CI (žr. {@code .github/workflows/eval-b6.yml}) - nustato TIKRĄ
 * autentifikuotą Spring Security sesiją fiksuotam testiniam FREE/PREMIUM vartotojui, tiksliai
 * tokiam pačiam, kokį minta {@link EvalTokenService} (ta pati {@code mint()} - tas pats vartotojo
 * provizionavimas, feature granting ir tier'o išvedimas, TAS PATS kelias kaip tikras
 * {@code eu.atsidek.chat.ChatController}). Egzistuoja TIK {@code eval-ci} profilyje - žr.
 * {@link EvalTokenService}'o javadoc dėl pilnos saugumo argumentacijos (profilio izoliacija IR
 * {@code ApiKeyFilter}, dvi nepriklausomos apsaugos).
 * <p>
 * Skirtingai nuo {@link EvalTokenService} (minta kontekstinį žetoną tiesioginiam Python
 * {@code /internal/chat} kvietimui), ŠITAS egzistuoja tam, kad {@code run_eval.py --via-endpoint}
 * galėtų siųsti žinutes per TIKRĄ Java {@code /chat/send} - tas endpoint'as tier'ą sprendžia
 * IŠIMTINAI iš Spring Security sesijos ({@code CurrentAppUser#optionalId()}), ne iš jokio
 * užklausos lauko, tad be šios klasės nebūtų jokio CI būdo pasiekti {@code /chat/send} kaip
 * prisijungęs vartotojas.
 * <p>
 * Sesija pažymima {@link #EVAL_SESSION_ATTR} atributu ir turi trumpą galiojimą
 * ({@link #SESSION_MAX_INACTIVE_SECONDS}) - minima prieš pat vieną eval'o kvietimą, niekada
 * neskirta ilgiau gyvuoti už vieną CI paleidimą.
 */
@Service
@Profile("eval-ci")
public class EvalSessionService {

    static final int SESSION_MAX_INACTIVE_SECONDS = 60;
    static final String EVAL_SESSION_ATTR = "eval_session";

    private final EvalTokenService evalTokenService;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public EvalSessionService(EvalTokenService evalTokenService) {
        this.evalTokenService = evalTokenService;
    }

    public record Session(String derivedTier, UUID userId) {
    }

    /**
     * @throws org.springframework.web.server.ResponseStatusException 400, jei {@code testUserKey}
     *                                 nėra {@code "free"} ar {@code "premium"} (žr.
     *                                 {@link EvalTokenService#mint}, kurio validacija čia
     *                                 pakartotinai naudojama, ne dubliuojama).
     */
    public Session establish(String testUserKey, HttpServletRequest request, HttpServletResponse response) {
        EvalTokenService.Minted minted = evalTokenService.mint(testUserKey);

        Authentication authentication = buildAuthentication(minted.userId(), testUserKey);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        HttpSession session = request.getSession(true);
        session.setAttribute(EVAL_SESSION_ATTR, Boolean.TRUE);
        session.setMaxInactiveInterval(SESSION_MAX_INACTIVE_SECONDS);

        return new Session(minted.derivedTier(), minted.userId());
    }

    /**
     * Tos pačios formos sintetiniai claims'ai, kaip testų pagalbinis {@code AppOidcUser} kūrėjas
     * (žr. {@code ChatControllerTest}) - {@code "sub"}/{@code "email"}/{@code "email_verified"} yra
     * vieninteliai claims'ai, kuriuos iš tikrųjų skaito {@link AppOidcUser}'o deleguojami metodai.
     */
    private static Authentication buildAuthentication(UUID userId, String testUserKey) {
        String subject = "eval-ci-" + testUserKey;
        OidcIdToken idToken = new OidcIdToken("eval-ci-token", Instant.now(),
                Instant.now().plusSeconds(SESSION_MAX_INACTIVE_SECONDS),
                Map.of("sub", subject, "email", subject + "@eval-ci.invalid", "email_verified", true));
        DefaultOidcUser delegate = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("SCOPE_openid")), idToken);
        AppOidcUser principal = new AppOidcUser(delegate, userId, false);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
