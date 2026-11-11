package eu.atsidek.goals.tracking.web;

import eu.atsidek.auth.CurrentAppUser;
import eu.atsidek.entitlement.EntitlementService;
import eu.atsidek.entitlement.Feature;
import eu.atsidek.goals.GoalInput;
import eu.atsidek.goals.tracking.SavingsGoal;
import eu.atsidek.goals.tracking.SavingsGoalService;
import eu.atsidek.goals.tracking.SavingsGoalService.SaveResult;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * ROLE_USER taupymo tikslo valdiklis (PLAN.md §5/§6 task 10). Kiekvienas metodas: pirmiausia
 * {@code EntitlementService.hasFeature} (trūksta → {@code 403} - paties funkcijos egzistavimas
 * nėra paslaptis, žr. §5/§7), tada savininkystei apribota užklausa per {@code SavingsGoalStore}
 * (trūksta arba svetima → {@code 404}, niekada {@code 403} - IDOR apsauga). {@code POST
 * /goals/tikslai} šaltinis visada - sesijoje laukiantis skaičiavimas, populintas per {@code
 * /goals/tikslai/preliminarus} (task 11); kūno formoje tik {@code confirm} varnelė/laukas, nes visa
 * šios funkcijos duomenų įvedimo eiga eina per nemokamą skaičiuoklę + sesijos perkėlimą (§5).
 */
@Controller
public class SavingsGoalController {

    private final SavingsGoalService service;
    private final EntitlementService entitlementService;
    private final CurrentAppUser currentAppUser;

    public SavingsGoalController(SavingsGoalService service, EntitlementService entitlementService,
                                  CurrentAppUser currentAppUser) {
        this.service = service;
        this.entitlementService = entitlementService;
        this.currentAppUser = currentAppUser;
    }

    @PostMapping("/goals/tikslai")
    public String save(@RequestParam(defaultValue = "false") boolean confirm, HttpSession session, Model model) {
        UUID userId = requireEntitlement();
        GoalInput pending = (GoalInput) session.getAttribute(SavingsGoalService.PENDING_INPUT_SESSION_KEY);
        if (pending == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nėra laukiančio skaičiavimo – pirmiausia atlikite skaičiavimą /taupymo-tikslai puslapyje");
        }
        SaveResult result = service.save(userId, pending, confirm);
        if (result.needsConfirmation()) {
            model.addAttribute("needsConfirmation", true);
            model.addAttribute("existingContributionCount", result.existingContributionCount());
            return "goals/tikslas";
        }
        session.removeAttribute(SavingsGoalService.PENDING_INPUT_SESSION_KEY);
        addGoalAndProgress(model, result.goal());
        return "goals/tikslas";
    }

    @GetMapping("/goals/tikslai/{id}")
    public String view(@PathVariable UUID id, Model model) {
        UUID userId = requireEntitlement();
        SavingsGoal goal = service.findOwnGoal(userId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        addGoalAndProgress(model, goal);
        return "goals/tikslas";
    }

    @PostMapping("/goals/tikslai/{id}/irasai")
    public String recordContribution(@PathVariable UUID id, @RequestParam String month,
                                      @RequestParam BigDecimal amount, Model model) {
        UUID userId = requireEntitlement();
        SavingsGoal goal = service.findOwnGoal(userId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        service.recordContribution(goal, YearMonth.parse(month), amount);
        addGoalAndProgress(model, goal);
        return "goals/tikslas";
    }

    private void addGoalAndProgress(Model model, SavingsGoal goal) {
        model.addAttribute("goal", goal);
        model.addAttribute("progress", service.progress(goal));
    }

    private UUID requireEntitlement() {
        UUID userId = currentAppUser.id();
        if (!entitlementService.hasFeature(userId, Feature.SAVINGS_TRACKING)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return userId;
    }
}
