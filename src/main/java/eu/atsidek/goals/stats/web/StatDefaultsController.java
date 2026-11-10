package eu.atsidek.goals.stats.web;

import eu.atsidek.goals.stats.StatDefaultsResponse;
import eu.atsidek.goals.stats.StatDefaultsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Viešas JSON endpoint'as naršyklės JS formai (PLAN.md K3). Trūkstamus laukus forma užpildo iš čia prieš
 * skaičiuodama – žr. {@code eu.atsidek.goals.web.GoalsController} (F1), prie kurio šis endpoint'as
 * prisijungs kitame žingsnyje.
 */
@RestController
public class StatDefaultsController {

    private final StatDefaultsService service;

    public StatDefaultsController(StatDefaultsService service) {
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
