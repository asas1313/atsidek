package lt.atsidek.web;

import java.io.IOException;
import lt.atsidek.engine.TaxComparison;
import lt.atsidek.engine.TaxEngine;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class CalculatorController {

    private final TaxEngine engine = TaxEngine.forYear2026();
    private final SignupStore signups;

    public CalculatorController(SignupStore signups) {
        this.signups = signups;
    }

    @GetMapping("/")
    public String index(@ModelAttribute("form") CalculatorForm form, Model model) {
        addComparison(form, model);
        return "index";
    }

    /** htmx: grąžina tik rezultato fragmentą. */
    @PostMapping("/calculate")
    public String calculate(@ModelAttribute("form") CalculatorForm form, Model model,
                            @RequestHeader(value = "HX-Request", required = false) String hx) {
        addComparison(form, model);
        return hx != null ? "index :: result" : "index";
    }

    @PostMapping("/signup")
    public String signup(@RequestParam String email,
                         @RequestParam(defaultValue = "false") boolean consent,
                         Model model) throws IOException {
        if (!SignupStore.isValid(email)) {
            model.addAttribute("signupError", "Patikrink el. pašto adresą.");
        } else if (!consent) {
            model.addAttribute("signupError", "Reikalingas sutikimas gauti pranešimą.");
        } else {
            signups.add(email);
            model.addAttribute("signupOk", true);
        }
        return "index :: signup";
    }

    private void addComparison(CalculatorForm form, Model model) {
        TaxComparison c = engine.compare(form.toInput());
        model.addAttribute("c", c);
        boolean actual = form.toInput().method() == lt.atsidek.engine.ExpenseMethod.ACTUAL;
        model.addAttribute("r", actual ? c.actual() : c.flat());
        model.addAttribute("hasActual", form.toInput().actualExpenses().signum() > 0);
        model.addAttribute("saving", c.flat().total().subtract(c.actual().total()));
    }
}
