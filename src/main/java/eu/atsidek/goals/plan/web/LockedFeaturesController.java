package eu.atsidek.goals.plan.web;

import eu.atsidek.auth.CurrentAppUser;
import eu.atsidek.goals.plan.SavingsPlanService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Viešas JSON endpoint'as {@code taupymo-tikslai.html} JS formai (A5, PLAN-taupymas-agentas.md). Tas
 * pats raštas kaip {@code eu.atsidek.goals.stats.web.StatDefaultsController} - plonas HTTP sluoksnis,
 * logika (kuri {@code Feature} užrakinta dabartiniam vartotojui) gyvena {@link SavingsPlanService},
 * kurį naudoja ir {@code POST /api/v1/savings/plan} (A0 sprendimas #1: UI kvietimas prisijungti
 * generuojamas iš TO PAČIO serviso {@code locked_features}, ne atskiros kopijos). Esamas
 * {@code /goals/calculate} kontraktas šiuo endpoint'u nekeičiamas (decision #1 scope boundary) - jis
 * tik PAPILDO puslapio JS nauja, atskira užklausa.
 * <p>
 * Be API rakto (ne po {@code /api/**}) - naršyklės JS fetch'as neturi API rakto, kitaip nei Python AI
 * servisas, kuris {@code locked_features} gauna per pilną {@code POST /api/v1/savings/plan} atsakymą.
 */
@RestController
public class LockedFeaturesController {

    private final SavingsPlanService service;
    private final CurrentAppUser currentAppUser;

    public LockedFeaturesController(SavingsPlanService service, CurrentAppUser currentAppUser) {
        this.service = service;
        this.currentAppUser = currentAppUser;
    }

    @GetMapping("/goals/savings/locked-features")
    public LockedFeaturesResponse lockedFeatures() {
        return new LockedFeaturesResponse(service.lockedFeatures(currentAppUser.optionalId().orElse(null)));
    }
}
