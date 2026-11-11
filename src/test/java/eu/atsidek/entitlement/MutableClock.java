package eu.atsidek.entitlement;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Testams - laikrodis, kurį galima rankiniu būdu paslinkti į priekį, be {@code Thread.sleep}. Žr.
 * plano §6 task 5: "No test... uses Thread.sleep or waits on the wall clock".
 */
final class MutableClock extends Clock {

    private volatile Instant instant;

    MutableClock(Instant instant) {
        this.instant = instant;
    }

    void advance(Duration duration) {
        this.instant = this.instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
