package eu.atsidek.entitlement;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Įpurškiamas {@link Clock}, numatytasis {@code Clock.systemUTC()} (ne {@code systemDefaultZone()}) -
 * patvirtinta pataisa: saugojimas/palyginimas visada UTC, {@code Europe/Vilnius} taikomas tik rodant
 * reikšmę (Thymeleaf šablonuose). Testuose pakeičiamas fiksuotu/mutuojamu laikrodžiu.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
