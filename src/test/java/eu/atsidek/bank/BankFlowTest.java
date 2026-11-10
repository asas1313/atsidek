package eu.atsidek.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.atsidek.TestcontainersConfiguration;
import eu.atsidek.bank.store.BankStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BankFlowTest {

    MockMvc mvc;
    @Autowired BankStore store;
    static final String USER = CurrentUser.PREFIX + "test";
    @Autowired WebApplicationContext ctx;
    @Autowired ImportService imports;
    @Autowired SummaryService summaries;
    @Autowired ReviewService review;

    @BeforeEach
    void clean() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity())
                .defaultRequest(get("/").with(oidcLogin().idToken(t -> t.subject("test"))).with(csrf())).build();
        signIn();
        store.deleteAll(USER);
    }

    /** MockMvc po kiekvienos užklausos išvalo saugumo kontekstą, o servisai CurrentUser ima iš jo. */
    private static void signIn() {
        OidcIdToken token = OidcIdToken.withTokenValue("t").subject("test").issuedAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new OAuth2AuthenticationToken(
                new DefaultOidcUser(List.of(), token), List.of(), "google"));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    private static byte[] file(String name) throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/statements", name));
    }

    @Test
    void uploadPageIsOpen() throws Exception {
        mvc.perform(get("/importas")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Įkelk išrašą")));
    }

    @Test
    void uploadThenReviewThenConfirmThenSummary() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "oct.xml", "text/xml", file("camt053/sample-02.xml"));
        MvcResult res = mvc.perform(multipart("/importas").file(f))
                .andExpect(status().is3xxRedirection()).andReturn();
        String loc = res.getResponse().getRedirectedUrl();
        assertThat(loc).startsWith("/importai/");
        String importId = loc.substring("/importai/".length());

        mvc.perform(get(loc)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("UAB Pavyzdys")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Reikia jūsų sprendimo")));

        signIn();
        List<ReviewService.Group> groups = review.groups(importId);
        assertThat(groups).hasSize(4);   // UAB Pavyzdys, Jonas, MB Vienas, MB Du (išeinanti nerodoma)
        ReviewService.Group jonas = groups.stream().filter(g -> g.payer().equals("Jonas Jonaitis")).findFirst().orElseThrow();
        assertThat(jonas.category()).isEqualTo(Category.UNKNOWN);
        assertThat(jonas.bucket()).isEqualTo(ReviewService.Bucket.DECISION);
        assertThat(groups.get(0).payer()).isEqualTo("Jonas Jonaitis");   // „reikia sprendimo“ viršuje

        // „Patvirtinti viską“: UNKNOWN lieka nepatvirtintas
        var req = post("/importai/" + importId + "/patvirtinti").param("all", "1");
        for (ReviewService.Group g : groups) {
            req.param("ids_" + g.index(), g.idList()).param("cat_" + g.index(), g.category().name());
        }
        mvc.perform(req).andExpect(status().is3xxRedirection());

        signIn();
        int year = 2026;
        SummaryService.Summary s = summaries.summary(year, java.time.LocalDate.of(2026, 10, 20));
        // UAB Pavyzdys 1210 + MB Vienas 100 + MB Du 50 = 1360 (Jonas nepatvirtintas)
        assertThat(s.ytdIncome()).isEqualByComparingTo("1360.00");
        SummaryService.Month oct = s.months().get(9);
        assertThat(oct.business()).isEqualByComparingTo("1360.00");
        assertThat(oct.setAside()).isPositive();
        // mėnesio suma = metinio įverčio skirtumas (be apvalinimo klaidų)
        assertThat(s.months().stream().map(SummaryService.Month::setAside).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                .isEqualByComparingTo(oct.ytdTax());
        mvc.perform(get("/suvestine?year=2026")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Atsidėk")));
    }

    @Test
    void sameFileTwiceAddsNothing() throws Exception {
        byte[] data = file("camt053/sample-02.xml");
        ImportService.Report first = imports.importFile("a.xml", data, null);
        ImportService.Report second = imports.importFile("a.xml", data, null);
        assertThat(first.duplicatesSkipped()).isZero();
        assertThat(second.duplicatesSkipped()).isEqualTo(5);
        assertThat(store.imports(USER).stream().mapToInt(BankStore.ImportRow::txCount).sum()).isEqualTo(5);
    }

    @Test
    void userRuleAppliesToLaterImportsAndInternalTransferIsNotIncome() throws Exception {
        ImportService.Report r1 = imports.importFile("a.xml", file("camt053/sample-02.xml"), null);
        ReviewService.Group jonas = review.groups(r1.importId()).stream()
                .filter(g -> g.payer().equals("Jonas Jonaitis")).findFirst().orElseThrow();
        review.apply(List.of(new ReviewService.Decision(jonas.ids(), Category.PERSONAL, true, true)));
        assertThat(store.rules(USER)).hasSize(1);

        // antras importas (kita sąskaita, kiti mokėjimai) – Jonas jau pagal taisyklę
        String csv = "Type,Started Date,Completed Date,Description,Amount,Currency,State\n"
                + "TRANSFER,2026-11-02,2026-11-02,Payment from Jonas Jonaitis,25.00,EUR,COMPLETED\n";
        ImportService.Report r2 = imports.importFile("rev.csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
        BankStore.Tx t = store.incomingOfImport(USER, r2.importId()).get(0);
        assertThat(t.category()).isEqualTo(Category.PERSONAL);
        assertThat(t.confidence()).isEqualTo(1.0);
        assertThat(store.accounts(USER)).hasSize(2);

        // vidinis pervedimas: mokėtojo IBAN = vartotojo sąskaita
        String own = "LT12 7300 0101 2345 6789";
        String camt = Files.readString(Path.of("src/test/resources/statements/camt053/sample-08.xml"))
                .replace("<IBAN>LT12 7300 0101 2345 6789</IBAN>", "<IBAN>LT55 1111 2222 3333 4444</IBAN>")
                .replace("DE89370400440532013000", own);
        ImportService.Report r3 = imports.importFile("b.xml", camt.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
        BankStore.Tx internal = store.incomingOfImport(USER, r3.importId()).get(0);
        assertThat(internal.category()).isEqualTo(Category.INTERNAL_TRANSFER);
    }

    @Test
    void deleteAllLeavesNothingAndUsersAreIsolated() throws Exception {
        imports.importFile("a.xml", file("camt053/sample-02.xml"), null);
        imports.importFile("rev.csv", file("revolut/personal.csv"), null);
        assertThat(store.imports(USER)).hasSize(2);
        assertThat(store.imports("kitas-vartotojas")).isEmpty();
        store.deleteAll("kitas-vartotojas");                 // svetimo trynimas nieko neliečia
        assertThat(store.imports(USER)).hasSize(2);

        mvc.perform(post("/nustatymai/istrinti-viska")).andExpect(status().is3xxRedirection());
        assertThat(store.imports(USER)).isEmpty();
        assertThat(store.accounts(USER)).isEmpty();
        assertThat(store.rules(USER)).isEmpty();
        assertThat(store.existingDedupKeys(USER, List.of("x"))).isEmpty();
    }

    @Test
    void unsupportedFileShowsMessage() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "x.csv", "text/csv", "a;b\n1;2\n".getBytes());
        mvc.perform(multipart("/importas").file(f))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("neatpažįstame")));
    }

    @Test
    void minimumPsdAccruesOnlyInChosenMonths() throws Exception {
        imports.importFile("a.xml", file("camt053/sample-02.xml"), null);
        review.apply(review.groups(store.imports(USER).get(0).id()).stream()
                .map(g -> new ReviewService.Decision(g.ids(), g.category(), true, false)).toList());
        // visi 12 mėn. (numatyta): minimalus PSD pasiskirsto per mėnesius iki pirmų pajamų (sausis–rugsėjis)
        SummaryService.Summary all = summaries.summary(2026, java.time.LocalDate.of(2026, 10, 20));
        assertThat(all.months().get(0).hasData()).isTrue();
        assertThat(all.months().get(0).setAside()).isEqualByComparingTo("80.48");
        assertThat(all.months().get(8).setAside()).isEqualByComparingTo("80.48");
        mvc.perform(post("/nustatymai/psd").param("year", "2026").param("psdFrom", "10").param("psdTo", "12"))
                .andExpect(status().is3xxRedirection());
        signIn();
        SummaryService.Summary s =summaries.summary(2026, java.time.LocalDate.of(2026, 10, 20));
        // tik nuo spalio: ankstesni mėnesiai nerodomi, spalį – vienas minimalaus PSD mėnuo
        assertThat(s.months().get(0).hasData()).isFalse();
        assertThat(s.months().get(8).hasData()).isFalse();
        assertThat(s.months().get(9).setAside()).isEqualByComparingTo(all.months().get(9).setAside());
        // kiti metai nepaveikti: nustatymas galioja tik 2026 m.
        assertThat(summaries.psdFrom(2026)).isEqualTo(10);
        assertThat(summaries.psdFrom(2025)).isEqualTo(1);
        // po paskutinių pajamų: PSD skaičiuojamas iki šiandienos (ne vėliau), laikantis „iki“ ribos
        SummaryService.Summary dec = summaries.summary(2026, java.time.LocalDate.of(2026, 12, 5));
        assertThat(dec.months().get(10).setAside()).isEqualByComparingTo("80.48");
        assertThat(dec.months().get(11).setAside()).isEqualByComparingTo("80.48");
        assertThat(summaries.summary(2026, java.time.LocalDate.of(2026, 11, 5)).months().get(11).hasData()).isFalse();
    }
}
