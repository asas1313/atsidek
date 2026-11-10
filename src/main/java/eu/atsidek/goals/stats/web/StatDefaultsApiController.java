package eu.atsidek.goals.stats.web;

import eu.atsidek.goals.stats.StatDefaultsResponse;
import eu.atsidek.goals.stats.StatDefaultsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tas pats numatymų skaičiavimas serveris-serveriui programoms (Python AI servisui), tik po
 * {@code /api/**}, todėl automatiškai apsaugotas {@code ApiKeyFilter} – papildomai nieko daryti nereikia
 * (žr. {@code eu.atsidek.goals.web.GoalsApiController} F1 PR #22 tą patį sprendimą).
 */
@RestController
@RequestMapping("/api/v1")
public class StatDefaultsApiController {

    private final StatDefaultsService service;

    public StatDefaultsApiController(StatDefaultsService service) {
        this.service = service;
    }

    @GetMapping("/goals/stats/defaults")
    public StatDefaultsResponse defaults(
            @RequestParam(required = false) Integer members,
            @RequestParam(required = false) Integer workers,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String goalType) {
        return service.resolve(members, workers, region, goalType);
    }
}
