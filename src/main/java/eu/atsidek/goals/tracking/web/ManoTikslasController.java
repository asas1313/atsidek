package eu.atsidek.goals.tracking.web;

import eu.atsidek.auth.CurrentAppUser;
import eu.atsidek.entitlement.EntitlementService;
import eu.atsidek.entitlement.Feature;
import eu.atsidek.goals.GoalInput;
import eu.atsidek.goals.tracking.SavingsGoal;
import eu.atsidek.goals.tracking.SavingsGoalService;
import eu.atsidek.goals.web.GoalsService;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Sesijos perkėlimo valdiklis (PLAN.md §5/§6 task 11): viešas (autorizacijai) bet CSRF apsaugotas
 * {@code POST /goals/tikslai/preliminarus} saugo nemokamos skaičiuoklės rezultatą sesijoje,
 * {@code GET /mano-tikslas} (ROLE_USER) trijų šakų logika - NIEKADA tyliai nepakeičia esamo tikslo
 * (blokuojantis pataisymas, §5/§7) - ir {@code POST /goals/tikslai/laukiantis/atmesti}, kuris tik
 * išvalo laukiantį skaičiavimą, nieko neišsaugodamas.
 */
@Controller
public class ManoTikslasController {

    private final SavingsGoalService service;
    private final EntitlementService entitlementService;
    private final CurrentAppUser currentAppUser;
    private final GoalsService goalsService = new GoalsService();

    public ManoTikslasController(SavingsGoalService service, EntitlementService entitlementService,
                                  CurrentAppUser currentAppUser) {
        this.service = service;
        this.entitlementService = entitlementService;
        this.currentAppUser = currentAppUser;
    }

    @PostMapping("/goals/tikslai/preliminarus")
    @ResponseBody
    public ResponseEntity<Void> savePending(@RequestBody GoalInput input, HttpSession session) {
        goalsService.calculate(input); // ta pati validacija kaip /goals/calculate (400, jei netinka)
        session.setAttribute(SavingsGoalService.PENDING_INPUT_SESSION_KEY, input);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/mano-tikslas")
    public String manoTikslas(HttpSession session, Model model) {
        UUID userId = currentAppUser.id();
        if (!entitlementService.hasFeature(userId, Feature.SAVINGS_TRACKING)) {
            model.addAttribute("noAccess", true);
            return "mano-tikslas";
        }

        GoalInput pending = (GoalInput) session.getAttribute(SavingsGoalService.PENDING_INPUT_SESSION_KEY);
        Optional<SavingsGoal> existing = service.findOwnGoal(userId);

        if (pending == null) {
            if (existing.isPresent()) {
                addGoalAndProgress(model, existing.get());
            } else {
                model.addAttribute("empty", true);
            }
            return "mano-tikslas";
        }

        if (existing.isEmpty()) {
            // Niekas nebūtų sunaikinta - saugo be papildomo patvirtinimo (task 9: confirmed moot).
            SavingsGoalService.SaveResult result = service.save(userId, pending, true);
            session.removeAttribute(SavingsGoalService.PENDING_INPUT_SESSION_KEY);
            addGoalAndProgress(model, result.goal());
            return "mano-tikslas";
        }

        // Laukiantis skaičiavimas IR esamas tikslas - NIEKAS nekeičiamas, kol vartotojas pats
        // nepasirenka (blokuojantis pataisymas, §5/§7) - nei tikslas, nei sesijos atributas.
        model.addAttribute("choice", true);
        model.addAttribute("existingContributionCount", service.contributionCount(existing.get().id()));
        return "mano-tikslas";
    }

    @PostMapping("/goals/tikslai/laukiantis/atmesti")
    public String rejectPending(HttpSession session) {
        session.removeAttribute(SavingsGoalService.PENDING_INPUT_SESSION_KEY);
        return "redirect:/mano-tikslas";
    }

    private void addGoalAndProgress(Model model, SavingsGoal goal) {
        model.addAttribute("goal", goal);
        model.addAttribute("progress", service.progress(goal));
    }
}
