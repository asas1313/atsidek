package lt.atsidek.web;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Paprastas atminties ribotuvas: ne daugiau kaip {@value #MAX} bandymų per valandą vienam raktui (IP). */
@Component
public class RateLimiter {

    static final int MAX = 5;
    private static final long WINDOW_MS = Duration.ofHours(1).toMillis();

    private record Window(long start, int count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public boolean allow(String key) {
        long now = System.currentTimeMillis();
        if (windows.size() > 10_000) {
            for (Iterator<Window> it = windows.values().iterator(); it.hasNext(); ) {
                if (now - it.next().start() > WINDOW_MS) {
                    it.remove();
                }
            }
        }
        Window w = windows.merge(key, new Window(now, 1),
                (old, fresh) -> now - old.start() > WINDOW_MS ? fresh : new Window(old.start(), old.count() + 1));
        return w.count() <= MAX;
    }
}
