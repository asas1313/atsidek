package eu.atsidek.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Atmintyje laikomas ribotuvas: slenkantis langas (1 val.) vienam IP + bendras paros limitas kainai apsaugoti. */
@Component
public class RateLimiter {

    public enum Decision { ALLOWED, IP_LIMIT, DAILY_CAP }

    private static final long WINDOW_MS = Duration.ofHours(1).toMillis();
    private static final int MAX_TRACKED_IPS = 10_000;

    private final Clock clock;
    private final int perHour;
    private final int dailyCap;
    private final Map<String, Deque<Long>> hits = new HashMap<>();
    private LocalDate day;
    private int dayCount;

    @Autowired
    public RateLimiter(@Value("${atsidek.ai.rate-limit-per-hour:10}") int perHour,
                       @Value("${atsidek.ai.daily-cap:300}") int dailyCap) {
        this(perHour, dailyCap, Clock.systemDefaultZone());
    }

    RateLimiter(int perHour, int dailyCap, Clock clock) {
        this.perHour = perHour;
        this.dailyCap = dailyCap;
        this.clock = clock;
    }

    public synchronized Decision tryAcquire(String ip) {
        long now = clock.millis();
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(day)) {
            day = today;
            dayCount = 0;
        }
        if (hits.size() > MAX_TRACKED_IPS) {
            hits.values().removeIf(q -> q.isEmpty() || now - q.peekLast() > WINDOW_MS);
        }
        Deque<Long> q = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) {
            q.pollFirst();
        }
        if (q.size() >= perHour) {
            return Decision.IP_LIMIT;
        }
        if (dayCount >= dailyCap) {
            return Decision.DAILY_CAP;
        }
        q.addLast(now);
        dayCount++;
        return Decision.ALLOWED;
    }
}
