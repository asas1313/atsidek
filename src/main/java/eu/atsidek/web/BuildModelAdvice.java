package eu.atsidek.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Puslapio apačioje rodo versiją (1.0.<PR>); git SHA (vaizdo žyma) tik paslėptame atribute. */
@ControllerAdvice
public class BuildModelAdvice {

    private final String version;
    private final String build;

    public BuildModelAdvice(@Value("${atsidek.version:1.0.0-dev}") String version,
                            @Value("${atsidek.build:dev}") String build) {
        this.version = version;
        this.build = build;
    }

    @ModelAttribute("appVersion")
    String appVersion() {
        return version;
    }

    @ModelAttribute("buildSha")
    String buildSha() {
        return build;
    }
}
