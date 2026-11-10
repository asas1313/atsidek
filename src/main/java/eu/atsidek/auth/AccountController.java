package eu.atsidek.auth;

import eu.atsidek.entitlement.AppUser;
import eu.atsidek.entitlement.AppUserStore;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * Savitarnos paskyros puslapis (PLAN.md §6 task 13, §4.7). {@code /paskyra} ir
 * {@code /paskyra/istrinti} jau yra {@code SecurityConfig}'o {@code hasRole("USER")} sąraše (task 6).
 * Trynimas pasikliauja {@code ON DELETE CASCADE} (ADR-0002) - {@code entitlement},
 * {@code savings_goal} ir per jį {@code savings_contribution} eilutės dingsta kartu su
 * {@code app_user} eilute, viena SQL operacija, be papildomų rankinių trynimų kiekvienai lentelei.
 */
@Controller
public class AccountController {

    private final AppUserStore appUserStore;
    private final CurrentAppUser currentAppUser;

    public AccountController(AppUserStore appUserStore, CurrentAppUser currentAppUser) {
        this.appUserStore = appUserStore;
        this.currentAppUser = currentAppUser;
    }

    @GetMapping("/paskyra")
    public String page(Model model) {
        AppUser user = appUserStore.findById(currentAppUser.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("email", user.email());
        return "paskyra";
    }

    @PostMapping("/paskyra/istrinti")
    public String delete(HttpSession session) {
        appUserStore.deleteById(currentAppUser.id());
        session.invalidate();
        return "redirect:/";
    }
}
