package eu.atsidek.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * {@link ClockConfig}'s default {@link Clock} bean must be {@code Clock.systemUTC()} (not
 * {@code systemDefaultZone()}) per the plan's explicit correction - storage/comparison stays in UTC,
 * {@code Europe/Vilnius} is applied only when displaying a value.
 */
class ClockConfigTest {

    @Test
    void defaultClockIsSystemUtc() {
        Clock clock = new ClockConfig().clock();

        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        assertThat(Duration.between(clock.instant(), Instant.now()).abs()).isLessThan(Duration.ofSeconds(5));
    }
}
