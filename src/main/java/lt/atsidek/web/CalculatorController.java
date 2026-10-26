package lt.atsidek.web;

import lt.atsidek.engine.TaxComparison;
import lt.atsidek.engine.TaxEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;

@Controller
public class CalculatorController {

    private final TaxEngine engine = TaxEngine.forYear2026();
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

    @GetMapping("/privatumas")
    public String privacy(Model model,
                          @Value("${atsidek.controller-name:[PRIVALOMA UŽPILDYTI: valdytojo pavadinimas]}") String name,
                          @Value("${atsidek.contact-email:[PRIVALOMA UŽPILDYTI: el. paštas]}") String email) {
        model.addAttribute("controllerName", name);
        model.addAttribute("contactEmail", email);
        return "privatumas";
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
