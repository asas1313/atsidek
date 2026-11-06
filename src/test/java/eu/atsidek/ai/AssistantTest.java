package eu.atsidek.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "atsidek.ai.rate-limit-per-hour=1000")
@AutoConfigureMockMvc
class AssistantTest {

    @TestConfiguration
    static class Fake {
        @Bean
        @Primary
        Extractor fakeExtractor() {
            return text -> {
                if (text.contains("klaida")) {
                    throw new Extractor.ExtractionException("AI paslauga nepasiekiama");
                }
                if (text.contains("nieko")) {
                    return new Extraction(null, null, "nezinoma", "", null, null, null, List.of("pajamos"));
                }
                if (text.contains("darbas")) {
                    return new Extraction(new BigDecimal("30000"), null, "30_proc", "metai",
                            new BigDecimal("15000"), true, 6, List.of());
                }
                return new Extraction(new BigDecimal("2400"), new BigDecimal("300"), "nezinoma",
                        "vienas projektas", null, null, null, List.of("tikslios išlaidos"));
            };
        }
    }

    @Autowired MockMvc mvc;

    @Test
    void homePageIsPublicAssistant() throws Exception {
        String html = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Aprašyk savo situaciją", "/skaiciuokle");
    }

    @Test
    void showsExtractionAndTaxResultWithoutLogin() throws Exception {
        String html = mvc.perform(post("/asistentas").header("HX-Request", "true").param("text", "Gavau 2400"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Ką supratau", "2 400,00 €", "Atsidėk kas mėnesį", "vienas projektas", "tikslios išlaidos");
        assertThat(html).doesNotContain("<html");
    }

    @Test
    void usesExtraParametersWhenGiven() throws Exception {
        String base = mvc.perform(post("/asistentas").header("HX-Request", "true").param("text", "Gavau 2400"))
                .andReturn().getResponse().getContentAsString();
        String extra = mvc.perform(post("/asistentas").header("HX-Request", "true").param("text", "darbas"))
                .andReturn().getResponse().getContentAsString();
        assertThat(extra).contains("15 000,00 €", "II pakopos pensijų kaupimas", "Pensijų kaupimas (II pakopa)",
                "Mėnesiai, kai pats moki PSD");
        assertThat(base).doesNotContain("Pensijų kaupimas (II pakopa)");
    }

    @Test
    void showsErrors() throws Exception {
        for (String[] c : new String[][] {{"klaida", "nepasiekiama"}, {"nieko", "Be pajamų"}, {"  ", "Parašyk"}}) {
            String html = mvc.perform(post("/asistentas").param("text", c[0]))
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).contains(c[1]);
        }
    }

    @Test
    void rateLimiterLimitsPerIpAndPerDay() {
        long[] now = {Instant.parse("2026-11-06T10:00:00Z").toEpochMilli()};
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now[0]); }
        };
        RateLimiter l = new RateLimiter(2, 3, clock);
        assertThat(l.tryAcquire("a")).isEqualTo(RateLimiter.Decision.ALLOWED);
        assertThat(l.tryAcquire("a")).isEqualTo(RateLimiter.Decision.ALLOWED);
        assertThat(l.tryAcquire("a")).isEqualTo(RateLimiter.Decision.IP_LIMIT);
        assertThat(l.tryAcquire("b")).isEqualTo(RateLimiter.Decision.ALLOWED);
        assertThat(l.tryAcquire("c")).isEqualTo(RateLimiter.Decision.DAILY_CAP);
        now[0] += Duration.ofHours(15).toMillis();
        assertThat(l.tryAcquire("a")).isEqualTo(RateLimiter.Decision.ALLOWED);
    }
}
