package eu.atsidek.evalci;

import eu.atsidek.auth.ContextTokenService;
import eu.atsidek.entitlement.AppUser;
import eu.atsidek.entitlement.AppUserStore;
import eu.atsidek.entitlement.EntitlementStore;
import eu.atsidek.entitlement.Feature;
import eu.atsidek.goals.plan.SavingsPlanService;
import eu.atsidek.goals.plan.Tier;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * TIK B6 gyvo agento eval'o CI (žr. {@code .github/workflows/eval-b6.yml}) - minta tikrą,
 * {@link ContextTokenService}'o pasirašytą kontekstinį žetoną fiksuotam testiniam FREE/PREMIUM
 * vartotojui tuščioje, vienkartinėje CI PostgreSQL. Egzistuoja TIK {@code eval-ci} profilyje
 * (niekada staging/production - žr. {@code application.yml}, kur šis profilis neminimas nė
 * vienoje realioje aplinkoje); net jei profilis būtų klaidingai aktyvuotas, jo vienintelis
 * kviečiantis kelias ({@link EvalTokenController}, {@code /api/v1/eval/token}) papildomai lieka
 * už {@code /api/**} {@code ApiKeyFilter} (žr. {@code SecurityConfig}), tiksliai kaip kiti
 * {@code /api/v1/**} keliai - dvi nepriklausomos apsaugos, ne viena.
 * <p>
 * Tier'as šiam testiniam vartotojui NIEKADA tiesiog "užrašomas" į žetoną - minta PER TĄ PATĮ kelią
 * kaip realus {@code eu.atsidek.chat.ChatController}
 * ({@code SavingsPlanService.deriveTier(lockedFeatures(userId))}), kad testinis žetonas tiksliai
 * atitiktų, ką gautų tikras tokios pačios entitlement-būsenos vartotojas - PREMIUM testiniam
 * vartotojui suteikiama KIEKVIENA {@link Feature} reikšmė (ne vien
 * {@code SavingsPlanService}'o privatus {@code GATED_FEATURES} sąrašas, kurio iš kito paketo
 * negalima perskaityti be dubliavimo rizikos) - taip {@code lockedFeatures(userId)} garantuotai
 * tuščias nepriklausomai nuo to, kurios funkcijos šiandien iš tiesų yra "gated", ir šis testinis
 * vartotojas niekada negali nebeatspindėti PREMIUM būsenos, jei GATED_FEATURES sąrašas pasikeistų.
 * Sąmoningai ne globalus beta flag'as (jis paveiktų ir kitus hipotetinius vartotojus šioje DB).
 */
@Service
@Profile("eval-ci")
public class EvalTokenService {

    /** Vienintelės leistinos {@code testUserKey} reikšmės - jokio laisvo UUID kūrimo iš užklausos. */
    public static final String FREE_KEY = "free";
    public static final String PREMIUM_KEY = "premium";

    private static final String AUTH_PROVIDER = "eval-ci";

    private final AppUserStore appUserStore;
    private final EntitlementStore entitlementStore;
    private final SavingsPlanService savingsPlanService;
    private final ContextTokenService contextTokenService;
    private final Clock clock;

    public EvalTokenService(AppUserStore appUserStore, EntitlementStore entitlementStore,
            SavingsPlanService savingsPlanService, ContextTokenService contextTokenService, Clock clock) {
        this.appUserStore = appUserStore;
        this.entitlementStore = entitlementStore;
        this.savingsPlanService = savingsPlanService;
        this.contextTokenService = contextTokenService;
        this.clock = clock;
    }

    public record Minted(String contextToken, String derivedTier, UUID userId) {
    }

    /**
     * @throws ResponseStatusException 400, jei {@code testUserKey} nėra {@link #FREE_KEY} ar
     *                                 {@link #PREMIUM_KEY}.
     */
    public Minted mint(String testUserKey) {
        if (!FREE_KEY.equals(testUserKey) && !PREMIUM_KEY.equals(testUserKey)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "test_user_key turi būti '" + FREE_KEY + "' arba '" + PREMIUM_KEY + "'");
        }
        UUID requestedId = deterministicUserId(testUserKey);
        AppUser user = appUserStore.upsert(requestedId, AUTH_PROVIDER, testUserKey,
                testUserKey + "@eval-ci.invalid");
        if (PREMIUM_KEY.equals(testUserKey)) {
            grantEveryFeature(user.id());
        }
        Tier tier = SavingsPlanService.deriveTier(savingsPlanService.lockedFeatures(user.id()));
        String token = contextTokenService.issue(user.id(), tier.name());
        return new Minted(token, tier.name(), user.id());
    }

    private void grantEveryFeature(UUID userId) {
        for (Feature feature : Feature.values()) {
            if (entitlementStore.findByUserAndFeature(userId, feature).isEmpty()) {
                entitlementStore.grant(userId, feature, "BETA", clock.instant());
            }
        }
    }

    /** Stabilus tarp kvietimų (vienas testinis vartotojas per raktą šioje CI DB), be atsitiktinumo. */
    private static UUID deterministicUserId(String testUserKey) {
        return UUID.nameUUIDFromBytes(("eval-ci-" + testUserKey).getBytes(StandardCharsets.UTF_8));
    }
}
