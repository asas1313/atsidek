package eu.atsidek.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.atsidek.bank.store.BankStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = {"atsidek.bank.enabled=true", "atsidek.bank.password=slaptas"})
@AutoConfigureMockMvc
class BankFlowTest {

    private static final String AUTH = "Basic dXNlcjpzbGFwdGFz"; // user:slaptas

    @Autowired MockMvc mvc;
    @Autowired BankStore store;
    @Autowired CurrentUser user;
    @Autowired ImportService imports;
    @Autowired SummaryService summaries;
    @Autowired ReviewService review;

    @BeforeEach
    void clean() {
        store.deleteAll(user.id());
    }

    private static byte[] file(String name) throws Exception {
        return Files.readAllBytes(Path.of("src/test/resources/statements", name));
    }

    @Test
    void requiresPasswordWhenConfigured() throws Exception {
        mvc.perform(get("/importas")).andExpect(status().isUnauthorized());
        mvc.perform(get("/importas").header("Authorization", "Basic dXNlcjpibG9nYXM=")).andExpect(status().isUnauthorized());
        mvc.perform(get("/importas").header("Authorization", AUTH)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Įkelk išrašą")));
    }

    @Test
    void uploadThenReviewThenConfirmThenSummary() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "oct.xml", "text/xml", file("camt053/sample-02.xml"));
        MvcResult res = mvc.perform(multipart("/importas").file(f).header("Authorization", AUTH))
                .andExpect(status().is3xxRedirection()).andReturn();
        String loc = res.getResponse().getRedirectedUrl();
        assertThat(loc).startsWith("/importai/");
        String importId = loc.substring("/importai/".length());

        mvc.perform(get(loc).header("Authorization", AUTH)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("UAB Pavyzdys")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Reikia jūsų sprendimo")));

        List<ReviewService.Group> groups = review.groups(importId);
        assertThat(groups).hasSize(4);   // UAB Pavyzdys, Jonas, MB Vienas, MB Du (išeinanti nerodoma)
        ReviewService.Group jonas = groups.stream().filter(g -> g.payer().equals("Jonas Jonaitis")).findFirst().orElseThrow();
        assertThat(jonas.category()).isEqualTo(Category.UNKNOWN);
        assertThat(jonas.bucket()).isEqualTo(ReviewService.Bucket.DECISION);
        assertThat(groups.get(0).payer()).isEqualTo("Jonas Jonaitis");   // „reikia sprendimo“ viršuje

        // „Patvirtinti viską“: UNKNOWN lieka nepatvirtintas
        var req = post("/importai/" + importId + "/patvirtinti").header("Authorization", AUTH).param("all", "1");
        for (ReviewService.Group g : groups) {
            req.param("ids_" + g.index(), g.idList()).param("cat_" + g.index(), g.category().name());
        }
        mvc.perform(req).andExpect(status().is3xxRedirection());

        int year = 2026;
        SummaryService.Summary s = summaries.summary(year);
        // UAB Pavyzdys 1210 + MB Vienas 100 + MB Du 50 = 1360 (Jonas nepatvirtintas)
        assertThat(s.ytdIncome()).isEqualByComparingTo("1360.00");
        SummaryService.Month oct = s.months().get(9);
        assertThat(oct.business()).isEqualByComparingTo("1360.00");
        assertThat(oct.setAside()).isPositive();
        // mėnesio suma = metinio įverčio skirtumas (be apvalinimo klaidų)
        assertThat(s.months().stream().map(SummaryService.Month::setAside).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add))
                .isEqualByComparingTo(oct.ytdTax());
        mvc.perform(get("/suvestine?year=2026").header("Authorization", AUTH)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Atsidėk")));
    }

    @Test
    void sameFileTwiceAddsNothing() throws Exception {
        byte[] data = file("camt053/sample-02.xml");
        ImportService.Report first = imports.importFile("a.xml", data, null);
        ImportService.Report second = imports.importFile("a.xml", data, null);
        assertThat(first.duplicatesSkipped()).isZero();
        assertThat(second.duplicatesSkipped()).isEqualTo(5);
        assertThat(store.imports(user.id()).stream().mapToInt(BankStore.ImportRow::txCount).sum()).isEqualTo(5);
    }

    @Test
    void userRuleAppliesToLaterImportsAndInternalTransferIsNotIncome() throws Exception {
        ImportService.Report r1 = imports.importFile("a.xml", file("camt053/sample-02.xml"), null);
        ReviewService.Group jonas = review.groups(r1.importId()).stream()
                .filter(g -> g.payer().equals("Jonas Jonaitis")).findFirst().orElseThrow();
        review.apply(List.of(new ReviewService.Decision(jonas.ids(), Category.PERSONAL, true, true)));
        assertThat(store.rules(user.id())).hasSize(1);

        // antras importas (kita sąskaita, kiti mokėjimai) – Jonas jau pagal taisyklę
        String csv = "Type,Started Date,Completed Date,Description,Amount,Currency,State\n"
                + "TRANSFER,2026-11-02,2026-11-02,Payment from Jonas Jonaitis,25.00,EUR,COMPLETED\n";
        ImportService.Report r2 = imports.importFile("rev.csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
        BankStore.Tx t = store.incomingOfImport(user.id(), r2.importId()).get(0);
        assertThat(t.category()).isEqualTo(Category.PERSONAL);
        assertThat(t.confidence()).isEqualTo(1.0);
        assertThat(store.accounts(user.id())).hasSize(2);

        // vidinis pervedimas: mokėtojo IBAN = vartotojo sąskaita
        String own = "LT12 7300 0101 2345 6789";
        String camt = Files.readString(Path.of("src/test/resources/statements/camt053/sample-08.xml"))
                .replace("<IBAN>LT12 7300 0101 2345 6789</IBAN>", "<IBAN>LT55 1111 2222 3333 4444</IBAN>")
                .replace("DE89370400440532013000", own);
        ImportService.Report r3 = imports.importFile("b.xml", camt.getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
        BankStore.Tx internal = store.incomingOfImport(user.id(), r3.importId()).get(0);
        assertThat(internal.category()).isEqualTo(Category.INTERNAL_TRANSFER);
    }

    @Test
    void deleteAllLeavesNothingAndUsersAreIsolated() throws Exception {
        imports.importFile("a.xml", file("camt053/sample-02.xml"), null);
        imports.importFile("rev.csv", file("revolut/personal.csv"), null);
        assertThat(store.imports(user.id())).hasSize(2);
        assertThat(store.imports("kitas-vartotojas")).isEmpty();
        store.deleteAll("kitas-vartotojas");                 // svetimo trynimas nieko neliečia
        assertThat(store.imports(user.id())).hasSize(2);

        mvc.perform(post("/nustatymai/istrinti-viska").header("Authorization", AUTH)).andExpect(status().is3xxRedirection());
        assertThat(store.imports(user.id())).isEmpty();
        assertThat(store.accounts(user.id())).isEmpty();
        assertThat(store.rules(user.id())).isEmpty();
        assertThat(store.existingDedupKeys(user.id(), List.of("x"))).isEmpty();
    }

    @Test
    void unsupportedFileShowsMessage() throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", "x.csv", "text/csv", "a;b\n1;2\n".getBytes());
        mvc.perform(multipart("/importas").file(f).header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("neatpažįstame")));
    }

    @Test
    void minimumPsdAccruesOnlyInChosenMonths() throws Exception {
        imports.importFile("a.xml", file("camt053/sample-02.xml"), null);
        review.apply(review.groups(store.imports(user.id()).get(0).id()).stream()
                .map(g -> new ReviewService.Decision(g.ids(), g.category(), true, false)).toList());
        // visi 12 mėn. (numatyta): minimalus PSD pasiskirsto per mėnesius iki pirmų pajamų (sausis–rugsėjis)
        SummaryService.Summary all = summaries.summary(2026);
        assertThat(all.months().get(0).hasData()).isTrue();
        assertThat(all.months().get(0).setAside()).isEqualByComparingTo("80.48");
        assertThat(all.months().get(8).setAside()).isEqualByComparingTo("80.48");
        mvc.perform(post("/nustatymai/psd").header("Authorization", AUTH).param("psdFrom", "10").param("psdTo", "12"))
                .andExpect(status().is3xxRedirection());
        SummaryService.Summary s = summaries.summary(2026);
        // tik nuo spalio: ankstesni mėnesiai nerodomi, spalį – vienas minimalaus PSD mėnuo
        assertThat(s.months().get(0).hasData()).isFalse();
        assertThat(s.months().get(8).hasData()).isFalse();
        assertThat(s.months().get(9).setAside()).isEqualByComparingTo(all.months().get(9).setAside());
    }
}
