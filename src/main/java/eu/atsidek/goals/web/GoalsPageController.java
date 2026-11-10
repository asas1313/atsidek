package eu.atsidek.goals.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Viešas HTML puslapis „Taupymo tikslai“ (F3, spec.md 6 skyrius). Forma pati (vanilla JS, PLAN.md K3)
 * kviečia {@code GET /goals/stats/defaults} ir {@code POST /goals/calculate} – šis kontroleris tik
 * atiduoda statinį Thymeleaf šabloną su meniu/nav fragmentais, jokio serverio pusės būsenos nėra.
 */
@Controller
public class GoalsPageController {

    @GetMapping("/taupymo-tikslai")
    public String page() {
        return "taupymo-tikslai";
    }
}
