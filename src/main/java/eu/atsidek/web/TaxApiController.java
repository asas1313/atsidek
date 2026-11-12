package eu.atsidek.web;

import eu.atsidek.auth.ContextTokenService;
import eu.atsidek.engine.ExpenseMethod;
import eu.atsidek.engine.TaxComparison;
import eu.atsidek.engine.TaxEngine;
import eu.atsidek.engine.TaxInput;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * JSON API programoms (pvz. AI asistentui): tas pats {@code TaxEngine}, kaip ir naršyklės skaičiuoklėje.
 * Visada grąžina abu išlaidų metodus (30 % ir faktines) bei geresnį.
 * <p>
 * Tapatybės žetonas (ADR-0003): {@code X-Atsidek-Context-Token} antraštė NEPRIVALOMA - kai jos nėra
 * (šiandieniniai Python {@code /asistentas} kvietimai per {@code AtsidekApi.compare()}), elgsena
 * TIKSLIAI tokia pati, kaip šiandien: tik {@code ApiKeyFilter}, be jokios papildomos tapatybės. Kai ji
 * yra - patikrinama {@link ContextTokenService#verify}; suklastota ar pasibaigusio galiojimo žetonas
 * atmetamas 401 (NIEKADA tyliai nukritęs į "žetono nėra" elgseną - suklastotas žetonas turi likti
 * suklastotu žetonu, ne tapti anonimu). Šis endpoint'as šiuo metu neturi tier'u priklausančios logikos,
 * todėl patikrinta tapatybė čia tik patvirtinama, bet nenaudojama skaičiavimui - skirtingai nuo
 * {@code SavingsPlanApiController}, kur ji iš tiesų lemia {@code locked_features}.
 */
@RestController
@RequestMapping("/api/v1")
public class TaxApiController {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000");

    private final TaxEngine engine = TaxEngine.forYear2026();
    private final ContextTokenService contextTokenService;

    public TaxApiController(ContextTokenService contextTokenService) {
        this.contextTokenService = contextTokenService;
    }

    /**
     * Užklausa be tylaus „taisymo“: {@link TaxInput} neigiamas sumas pakeistų nuliu, todėl čia jas atmetame.
     * Neprivalomi laukai: method (nenaudojamas palyginime), actualExpenses, otherIncome (0),
     * pensionAccumulation (false), psdMinMonths (12).
     */
    public record CompareRequest(
            BigDecimal ivRevenue,
            ExpenseMethod method,
            BigDecimal actualExpenses,
            BigDecimal otherIncome,
            Boolean pensionAccumulation,
            Integer psdMinMonths) {
    }

    @PostMapping("/compare")
    public TaxComparison compare(@RequestBody CompareRequest req,
            @RequestHeader(name = "X-Atsidek-Context-Token", required = false) String contextToken) {
        verifyContextTokenIfPresent(contextToken);
        List<String> errors = new ArrayList<>();
        if (req.ivRevenue() == null) {
            errors.add("ivRevenue privalomas");
        }
        checkAmount("ivRevenue", req.ivRevenue(), errors);
        checkAmount("actualExpenses", req.actualExpenses(), errors);
        checkAmount("otherIncome", req.otherIncome(), errors);
        Integer months = req.psdMinMonths();
        if (months != null && (months < 0 || months > 12)) {
            errors.add("psdMinMonths turi būti 0–12");
        }
        if (!errors.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", errors));
        }
        return engine.compare(new TaxInput(
                req.ivRevenue(),
                req.method(),
                req.actualExpenses(),
                req.otherIncome(),
                Boolean.TRUE.equals(req.pensionAccumulation()),
                months == null ? 12 : months));
    }

    /**
     * Antraštės nebuvimas - teisėtas, nepakitęs atvejis (žr. klasės javadoc). Jos buvimas IR
     * nesėkminga patikra - visada 401, niekada tylus kritimas į "elkis, kaip ir antraštės nebūtų".
     */
    private void verifyContextTokenIfPresent(String contextToken) {
        if (contextToken == null || contextToken.isBlank()) {
            return;
        }
        contextTokenService.verify(contextToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Negaliojantis arba pasibaigusio galiojimo kontekstinis žetonas"));
    }

    private static void checkAmount(String name, BigDecimal v, List<String> errors) {
        if (v == null) {
            return;
        }
        if (v.signum() < 0) {
            errors.add(name + " negali būti neigiamas");
        } else if (v.compareTo(MAX_AMOUNT) > 0) {
            errors.add(name + " per didelis");
        }
    }
}
