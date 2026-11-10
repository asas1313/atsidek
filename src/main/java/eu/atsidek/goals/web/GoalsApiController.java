package eu.atsidek.goals.web;

import eu.atsidek.goals.GoalInput;
import eu.atsidek.goals.GoalResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tas pats skaičiavimas serveris-serveriui programoms (Python AI servisui), tik po {@code /api/**}, todėl
 * automatiškai apsaugotas {@code ApiKeyFilter} (žr. {@code shouldNotFilter}) – šiam kontroleriui papildomai
 * nieko daryti nereikia.
 */
@RestController
@RequestMapping("/api/v1")
public class GoalsApiController {

    private final GoalsService service = new GoalsService();

    @PostMapping("/goals/calculate")
    public GoalResult calculate(@RequestBody GoalInput input) {
        return service.calculate(input);
    }
}
