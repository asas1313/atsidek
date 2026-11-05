package eu.atsidek;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AtsidekApplication {
    public static void main(String[] args) {
        SpringApplication.run(AtsidekApplication.class, args);
    }
}
