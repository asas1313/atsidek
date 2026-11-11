package eu.atsidek.goals.plan.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import eu.atsidek.auth.CurrentAppUser;
import eu.atsidek.goals.plan.SavingsPlanRequest;
import eu.atsidek.goals.plan.SavingsPlanResponse;
import eu.atsidek.goals.plan.SavingsPlanService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code POST /api/v1/savings/plan} (PLAN-taupymas-agentas.md A3) - plonas sluoksnis virš
 * {@link SavingsPlanService}: skaito tik tai, kas priklauso šiam sluoksniui (nežinomas vartotojas →
 * {@code null}, tier/core/premium logika - {@link SavingsPlanService}, decision #1).
 * <p>
 * Po {@code /api/**}, todėl automatiškai apsaugotas {@code ApiKeyFilter} (žr.
 * {@code eu.atsidek.goals.web.GoalsApiController}) - tai NEPRIKLAUSOMA nuo Spring Security
 * sesijos/prisijungimo: skambinantysis gali turėti galiojantį API raktą IR (nebūtinai) galiojančią
 * naršyklės sesiją tuo pačiu metu - pirmas tikrina {@code ApiKeyFilter}, antras - čia, per
 * {@link CurrentAppUser#optionalId()} (anonimas - teisėtas atvejis, ne klaida, A4 sprendimas #6).
 * <p>
 * Kūnas skaitomas kaip {@code String} ir verčiamas RANKA per {@link #STRICT_MAPPER} - NE per
 * standartinį {@code @RequestBody SavingsPlanRequest} susiejimą - nes patikrinta ({@code
 * SavingsPlanRequestJsonTest}), kad Spring Boot automatiškai sukonfigūruotas Jackson
 * {@code ObjectMapper} bean'as IŠJUNGIA {@code FAIL_ON_UNKNOWN_PROPERTIES}, o PLAN-taupymas-agentas.md
 * A3 reikalauja, kad nežinomi laukai → 400 (kaip {@code /api/v1/compare} *turėtų* elgtis - tas
 * endpoint'as čia NEKEIČIAMAS, žr. agento ataskaitą). Kadangi kūnas skaitomas kaip {@code String}
 * (priima {@code StringHttpMessageConverter}, kuris pats savaime leidžia bet kokį/jokį
 * {@code Content-Type}), {@code consumes = APPLICATION_JSON_VALUE} čia PRIVALOMAS, kitaip šis
 * endpoint'as (kitaip nei {@code /api/v1/compare}, kuris naudoja tipuotą {@code @RequestBody}) nebe
 * grąžintų 415 netinkamam {@code Content-Type} (rastas review metu).
 */
@RestController
@RequestMapping("/api/v1")
public class SavingsPlanApiController {

    /**
     * Grynas Jackson {@code ObjectMapper} - jo numatytasis {@code FAIL_ON_UNKNOWN_PROPERTIES} = true, be
     * papildomo konfigūravimo. {@code FAIL_ON_TRAILING_TOKENS} papildomai įjungtas RANKA - be jo galiojantis
     * JSON su šiukšlėmis po jo (pvz. {@code {"goal":{...}} garbage}) būtų tyliai priimtas.
     */
    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final SavingsPlanService service;
    private final CurrentAppUser currentAppUser;

    public SavingsPlanApiController(SavingsPlanService service, CurrentAppUser currentAppUser) {
        this.service = service;
        this.currentAppUser = currentAppUser;
    }

    @PostMapping(path = "/savings/plan", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SavingsPlanResponse plan(@RequestBody String rawBody) {
        SavingsPlanRequest request = parse(rawBody);
        return service.plan(request, currentAppUser.optionalId().orElse(null));
    }

    private static SavingsPlanRequest parse(String rawBody) {
        try {
            return STRICT_MAPPER.readValue(rawBody, SavingsPlanRequest.class);
        } catch (UnrecognizedPropertyException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nežinomas laukas: " + e.getPropertyName());
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Netinkamas užklausos JSON formatas");
        }
    }
}
