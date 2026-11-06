package eu.atsidek.ai;

import eu.atsidek.engine.ExpenseMethod;
import eu.atsidek.engine.TaxComparison;
import eu.atsidek.engine.TaxEngine;
import eu.atsidek.engine.TaxInput;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * AI asistentas: žmogus aprašo situaciją laisvu tekstu, modelis ištraukia skaičius,
 * mokesčius skaičiuoja deterministinis {@link TaxEngine} (modelis niekada neskaičiuoja).
 */
@Controller
public class AssistantController {

    static final int MAX_LENGTH = 1000;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000");

    private final TaxEngine engine = TaxEngine.forYear2026();
    private final Extractor extractor;
    private final RateLimiter limiter;

    public AssistantController(Extractor extractor, RateLimiter limiter) {
        this.extractor = extractor;
        this.limiter = limiter;
    }

    @GetMapping("/")
    public String page(Model model) {
        model.addAttribute("text", "");
        return "ai/assistant";
    }

    @PostMapping("/asistentas")
    public String ask(@RequestParam("text") String text, Model model, HttpServletRequest request,
                      @RequestHeader(value = "HX-Request", required = false) String hx) {
        String trimmed = text == null ? "" : text.strip();
        model.addAttribute("text", trimmed);
        model.addAttribute("asked", true);
        if (trimmed.isEmpty()) {
            model.addAttribute("error", "Parašyk, kokia tavo situacija.");
        } else if (trimmed.length() > MAX_LENGTH) {
            model.addAttribute("error", "Tekstas per ilgas (daugiausia " + MAX_LENGTH + " simbolių).");
        } else {
            RateLimiter.Decision d = limiter.tryAcquire(clientIp(request));
            if (d == RateLimiter.Decision.ALLOWED) {
                run(trimmed, model);
            } else {
                model.addAttribute("error", d == RateLimiter.Decision.IP_LIMIT
                        ? "Per daug užklausų iš tavo adreso. Pabandyk vėliau arba naudok skaičiuoklę."
                        : "Šiandien AI asistento limitas išnaudotas. Pabandyk rytoj arba naudok skaičiuoklę.");
            }
        }
        return hx != null ? "ai/assistant :: result" : "ai/assistant";
    }

    private void run(String text, Model model) {
        Extraction x;
        try {
            x = extractor.extract(text);
        } catch (Extractor.ExtractionException e) {
            model.addAttribute("error", e.getMessage());
            return;
        }
        model.addAttribute("x", x);
        if (x.revenue() == null) {
            model.addAttribute("error", "Be pajamų sumos paskaičiuoti negalima.");
            return;
        }
        if (invalid(x.revenue()) || (x.expenses() != null && invalid(x.expenses()))) {
            model.addAttribute("error", "Ištrauktos sumos neatrodo tinkamos (neigiamos arba per didelės). Patikslink tekstą.");
            return;
        }
        if (x.otherIncome() != null && invalid(x.otherIncome())) {
            model.addAttribute("error", "Ištrauktos kitos pajamos neatrodo tinkamos. Patikslink tekstą.");
            return;
        }
        BigDecimal expenses = x.expenses() == null ? BigDecimal.ZERO : x.expenses();
        BigDecimal other = x.otherIncome() == null ? BigDecimal.ZERO : x.otherIncome();
        boolean pillar2 = Boolean.TRUE.equals(x.pillar2());
        int psdMonths = x.psdMonths() == null ? 12 : Math.max(0, Math.min(12, x.psdMonths()));
        TaxComparison c = engine.compare(new TaxInput(x.revenue(), ExpenseMethod.FLAT_30, expenses,
                other, pillar2, psdMonths));
        model.addAttribute("other", other);
        model.addAttribute("pillar2", pillar2);
        model.addAttribute("psdMonths", psdMonths);
        boolean hasActual = expenses.signum() > 0;
        ExpenseMethod shown;
        if (!hasActual || "30_proc".equals(x.expenseMethod())) {
            shown = ExpenseMethod.FLAT_30;
        } else if ("faktines".equals(x.expenseMethod())) {
            shown = ExpenseMethod.ACTUAL;
        } else {
            shown = c.better().method();
        }
        model.addAttribute("c", c);
        model.addAttribute("hasActual", hasActual);
        model.addAttribute("r", shown == ExpenseMethod.ACTUAL ? c.actual() : c.flat());
        model.addAttribute("actualShown", shown == ExpenseMethod.ACTUAL);
    }

    /** Už Cloudflare / proxy: tikrasis kliento adresas iš antraštės, kitaip tiesioginis. */
    static String clientIp(HttpServletRequest r) {
        String cf = r.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) {
            return cf.trim();
        }
        String xff = r.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return r.getRemoteAddr();
    }

    private static boolean invalid(BigDecimal v) {
        return v.signum() < 0 || v.compareTo(MAX_AMOUNT) > 0;
    }
}
