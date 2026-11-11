package eu.atsidek.entitlement;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Vienintelis Java pusės patikros taškas: {@link #hasFeature(UUID, Feature)}. Kai globalus
 * beta režimas ({@code goals_beta_mode_enabled}) įjungtas, bet kuris prisijungęs vartotojas turi
 * visas funkcijas; priešingu atveju lemia tikri {@code entitlement} įrašai (šiuo metu visada
 * tušti, nes nieko jų dar neišduoda). Beta žymos skaitymas iš {@code app_setting} talpinamas
 * trumpam (~30s), naudojant įpurškiamą {@link Clock} - ne {@code System.currentTimeMillis()} ar
 * {@code Thread.sleep} testuose (žr. ADR-0002).
 */
@Service
public class EntitlementService {

    private static final String BETA_FLAG_KEY = "goals_beta_mode_enabled";
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final EntitlementStore store;
    private final Clock clock;

    private volatile boolean cachedBetaEnabled = false;
    private volatile Instant cachedAt;

    public EntitlementService(EntitlementStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public boolean hasFeature(UUID appUserId, Feature feature) {
        if (isBetaModeEnabled()) {
            return true;
        }
        Instant now = clock.instant();
        return store.findByUserAndFeature(appUserId, feature).stream()
                .anyMatch(e -> isValidAt(e, now));
    }

    private static boolean isValidAt(Entitlement entitlement, Instant now) {
        boolean started = !entitlement.validFrom().isAfter(now);
        boolean notExpired = entitlement.validUntil() == null || entitlement.validUntil().isAfter(now);
        return started && notExpired;
    }

    private synchronized boolean isBetaModeEnabled() {
        Instant now = clock.instant();
        if (cachedAt == null || now.isAfter(cachedAt.plus(CACHE_TTL))) {
            cachedBetaEnabled = "true".equalsIgnoreCase(store.findSettingValue(BETA_FLAG_KEY).orElse(""));
            cachedAt = now;
        }
        return cachedBetaEnabled;
    }
}
