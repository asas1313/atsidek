package eu.atsidek.chat;

import eu.atsidek.auth.ContextTokenService;
import eu.atsidek.auth.CurrentAppUser;
import eu.atsidek.goals.plan.SavingsPlanService;
import eu.atsidek.goals.plan.Tier;
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
 * B1 taikymo sritis TYČIA siaura (žr. plano "Scope boundary"): tikras daugiapakopis agento ciklas,
 * atsakymo formavimas ir pokalbio istorija yra B3/B4/B5 darbas, NE šio endpoint'o. Čia tik
 * įrodoma, kad žetonas teisingai nukeliauja į Python ir atgal - atsakymas tiesiog perduodamas iš
 * Python pusės (šiuo metu - stub/ack).
 */
@RestController
public class ChatController {

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
    public Object send(@RequestBody ChatSendRequest request) {
        String message = request.message() == null ? "" : request.message().trim();
        if (message.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message privalomas");
        }
        UUID userId = currentAppUser.optionalId().orElse(null);
        Tier tier = savingsPlanService.lockedFeatures(userId).isEmpty() ? Tier.PREMIUM : Tier.FREE;
        String contextToken = contextTokenService.issue(userId, tier.name());
        return assistantClient.chat(contextToken, message);
    }
}
