package eu.atsidek.chat;

import eu.atsidek.auth.ContextTokenService;
import eu.atsidek.auth.CurrentAppUser;
import eu.atsidek.goals.plan.SavingsPlanService;
import eu.atsidek.goals.plan.Tier;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code POST /chat/send} (PLAN-taupymas-agentas.B.plan.md B1, ADR-0003 "Accepted") - naujas AI
 * pokalbio endpoint'as, kurį kviečia naršyklė. Skirtingai nuo {@code /api/v1/compare}/
 * {@code /api/v1/savings/plan} (statinis API raktas, be tapatybės), šis endpoint'as yra
 * sesijos/OAuth sluoksnyje (žr. {@code SecurityConfig} - {@code permitAll()}, nes ir anonimas, ir
 * prisijungęs vartotojas yra teisėti atvejai, A4 sprendimas #6) ir PATS ASERTUOJA tapatybę Python
 * pusei: išsprendžia skambinančiojo tapatybę per {@link CurrentAppUser#optionalId()} (anonimas -
 * teisėtas atvejis) ir tier'ą per TĄ PATĮ vienintelį šaltinį kaip {@code SavingsPlanApiController}
 * ({@link SavingsPlanService#lockedFeatures}, A0 sprendimas #1 - NE atskira tier'o logikos kopija),
 * tada minta trumpaamžį pasirašytą kontekstinį žetoną ({@link ContextTokenService#issue}) ir kviečia
 * Python {@code assistant} servisą su juo.
 * <p>
 * B1 taikymo sritis buvo TYČIA siaura (žr. plano "Scope boundary") - tik įrodyti, kad žetonas
 * teisingai nukeliauja į Python ir atgal. B7 ("sujungimas") pridėjo tai, kas iki tol trūko: tier'as
 * ir stabilus vartotojo raktas siunčiami Python pusei kaip atskiri, nepasirašyti laukai (Python
 * NIEKADA nedekoduoja žetono - žr. {@link AssistantClient#chat}), o pokalbio istorija laikoma ČIA,
 * {@link HttpSession}, ir siunčiama/priimama kaip Python NEINTERPRETUOJAMAS JSON blokas (B5
 * sprendimas 7) - šis endpoint'as niekada pats neskaito/nekeičia jos turinio, tik laiko ir
 * persiunčia atgal tiksliai tą patį, ką Python paskutinį kartą grąžino {@code "messages"} rakte.
 */
@RestController
public class ChatController {

    /** B7: kur sesijoje laikoma pokalbio istorija (B5 sprendimas 7 - Java, ne Python, ją laiko). */
    static final String HISTORY_SESSION_ATTR = "atsidek.chat.history";

    /**
     * Review round 1 SHOULD #3: be šios ribos žinutės dydis buvo neribotas. Nėra Java pusės
     * atitikmens pakartotinai naudoti (ne {@code eu.atsidek.goals} paketas - ten tokios konstantos
     * nėra; ji yra tik Python pusėje, žr. {@code assistant/app/main.py}'s {@code MAX_LENGTH}) -
     * reikšmė sąmoningai sutampa su ja dėl simetrijos, nes abi riboja tą pačią laisvo teksto žinutę.
     */
    static final int MAX_LENGTH = 1000;

    private final CurrentAppUser currentAppUser;
    private final SavingsPlanService savingsPlanService;
    private final ContextTokenService contextTokenService;
    private final AssistantClient assistantClient;

    public ChatController(CurrentAppUser currentAppUser, SavingsPlanService savingsPlanService,
            ContextTokenService contextTokenService, AssistantClient assistantClient) {
        this.currentAppUser = currentAppUser;
        this.savingsPlanService = savingsPlanService;
        this.contextTokenService = contextTokenService;
        this.assistantClient = assistantClient;
    }

    public record ChatSendRequest(String message) {
    }

    @PostMapping("/chat/send")
    public Object send(@RequestBody ChatSendRequest request, HttpSession session) {
        String message = request.message() == null ? "" : request.message().trim();
        if (message.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message privalomas");
        }
        if (message.length() > MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "message per ilgas (daugiausia " + MAX_LENGTH + " simbolių)");
        }
        UUID userId = currentAppUser.optionalId().orElse(null);
        Tier tier = SavingsPlanService.deriveTier(savingsPlanService.lockedFeatures(userId));
        String contextToken = issueContextToken(userId, tier);
        String key = userId == null ? null : userId.toString();
        Object history = session.getAttribute(HISTORY_SESSION_ATTR);
        Object response = assistantClient.chat(contextToken, message, tier.name(), key, history);
        // B7: Python grąžina "messages" TIK sėkmingo agento paleidimo atveju (žr. main.py's
        // _agent_chat) - nei senas B1 stub (kol AGENT_CHAT_ENABLED=false), nei limito/klaidos
        // atsakymai šio rakto neturi, tad sesijos istorija jų atveju tiesiog nepaliečiama (nekorumpuojama
        // nepavykusiu posūkiu). Turinio NEINTERPRETUOJAME - tik laikome ir grąžiname atgal.
        if (response instanceof Map<?, ?> body && body.get("messages") != null) {
            session.setAttribute(HISTORY_SESSION_ATTR, body.get("messages"));
        }
        return response;
    }

    /**
     * Review round 1 NIT #6: {@link ContextTokenService#issue} tyčia meta
     * {@link IllegalStateException}, kai paslaptis nesukonfigūruota (fail closed) - be šio
     * {@code catch}, ta žinutė (su konfigūracijos raktų pavadinimais) nutekėtų į HTTP atsakymą kaip
     * žaliavinis stack trace. Čia verčiama į švarų, be detalių 500.
     */
    private String issueContextToken(UUID userId, Tier tier) {
        try {
            return contextTokenService.issue(userId, tier.name());
        } catch (IllegalStateException misconfigured) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "AI pokalbio paslauga laikinai nesukonfigūruota.", misconfigured);
        }
    }
}
