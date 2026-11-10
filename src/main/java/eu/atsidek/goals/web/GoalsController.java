package eu.atsidek.goals.web;

import eu.atsidek.goals.GoalInput;
import eu.atsidek.goals.GoalResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Viešas JSON endpoint'as naršyklės JS formai. PLAN.md K3: šis modulis – JSON + vanilla JS, ne htmx
 * fragmentas (kitaip nei {@code /calculate} mokesčių skaičiuoklėje).
 */
@RestController
public class GoalsController {

    private final GoalsService service = new GoalsService();

    @PostMapping("/goals/calculate")
    public GoalResult calculate(@RequestBody GoalInput input) {
        return service.calculate(input);
    }
}
