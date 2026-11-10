package eu.atsidek.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.atsidek.TestcontainersConfiguration;
import eu.atsidek.bank.store.BankStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthTest {

    @Autowired MockMvc mvc;
    @Autowired BankStore store;

    @Test
    void publicPagesStayOpen() throws Exception {
        for (String p : new String[] {"/skaiciuokle", "/privatumas", "/style.css"}) {
            mvc.perform(get(p)).andExpect(status().isOk());
        }
    }

    @Test
    void pagesShowBuildVersion() throws Exception {
        for (String p : new String[] {"/skaiciuokle", "/privatumas", "/straipsniai/30-proc-ar-faktines-islaidos"}) {
            assertThat(mvc.perform(get(p)).andReturn().getResponse().getContentAsString()).contains("Versija ");
        }
    }

    @Test
    void bankPagesRedirectAnonymousToGoogle() throws Exception {
        for (String p : new String[] {"/importas", "/suvestine", "/taisykles", "/nustatymai"}) {
            mvc.perform(get(p)).andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("http://localhost/oauth2/authorization/google"));
        }
    }

    @Test
    void navShowsAllItemsAlwaysAndLoginOrLogoutByState() throws Exception {
        String[] items = {"/importas", "/suvestine", "/taisykles", "/nustatymai"};
        String anon = mvc.perform(get("/skaiciuokle")).andReturn().getResponse().getContentAsString();
        assertThat(anon).contains("Prisijungti").doesNotContain("Atsijungti").contains(items);
        String in = mvc.perform(get("/skaiciuokle").with(oidcLogin())).andReturn().getResponse().getContentAsString();
        assertThat(in).contains("Atsijungti").contains(items).doesNotContain(">Prisijungti<");
    }

    @Test
    void googleAlwaysAsksWhichAccount() throws Exception {
        String loc = mvc.perform(get("/oauth2/authorization/google")).andReturn().getResponse().getRedirectedUrl();
        assertThat(loc).contains("prompt=select_account");
    }

    @Test
    void logoutEndsSessionAndReturnsHome() throws Exception {
        mvc.perform(post("/logout").with(oidcLogin()).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"));
    }

    @Test
    void anonymousPostIsNotAllowed() throws Exception {
        mvc.perform(post("/nustatymai/istrinti-viska")).andExpect(status().is4xxClientError());
    }

    /**
     * Plano §6 task 6: ne-leidžiamo (ne allow-list) bet patvirtinto el. pašto prisijungimas vis tiek
     * pasiekia {@code /mano-tikslas} (ROLE_USER) - saugumo sluoksnis nebeatmeta tokio prisijungimo
     * kaip "access_denied". {@code /mano-tikslas} valdiklis dar neegzistuoja (prideda task 11), tad
     * atsakymas bus 404 - svarbu čia tik tai, kad apsauga NEBLOKUOJA (ne 401/403, ne nukreipimas į
     * Google) paprasto ROLE_USER naudotojo.
     */
    @Test
    void nonAllowListedVerifiedLoginReachesManoTikslas() throws Exception {
        var result = mvc.perform(get("/mano-tikslas")
                        .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isNotIn(401, 403);
        assertThat(result.getResponse().getRedirectedUrl())
                .isNotEqualTo("http://localhost/oauth2/authorization/google");
    }

    /** Toks pat ROLE_USER (bet ne ROLE_ADMIN) prisijungimas gauna 403 banko ekranuose. */
    @Test
    void nonAllowListedVerifiedLoginGetsForbiddenOnBankPages() throws Exception {
        for (String p : new String[] {"/importas", "/suvestine", "/taisykles", "/nustatymai"}) {
            mvc.perform(get(p).with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isForbidden());
        }
    }

    /** Autentifikuotas ROLE_USER POST be CSRF žetono į naują apsaugotą /goals/tikslai yra atmestas. */
    @Test
    void postGoalsTikslaiWithoutCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/goals/tikslai")
                        .with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    /**
     * /goals/tikslai/preliminarus yra permitAll() autorizacijai, bet CSRF apsaugotas (blokuojantis
     * pataisymas, žr. plano §6/§7) - be CSRF žetono atmetamas, su žetonu CSRF filtras praleidžia
     * (valdiklis dar neegzistuoja, bus pridėtas task 11, tad atsakymas čia - 404, ne sėkmė).
     */
    @Test
    void postGoalsTikslaiPreliminarusWithoutCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/goals/tikslai/preliminarus")).andExpect(status().isForbidden());
    }

    @Test
    void postGoalsTikslaiPreliminarusWithCsrfTokenPassesCsrfCheck() throws Exception {
        mvc.perform(post("/goals/tikslai/preliminarus").with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void adoptLegacyMovesLocalDataOnce() {
        store.deleteAll("local");
        store.deleteAll("google:owner");
        store.setSetting("local", "k", "7");
        store.adoptLegacy("local", "google:owner");
        assertThat(store.intSetting("google:owner", "k", -1)).isEqualTo(7);
        assertThat(store.intSetting("local", "k", -1)).isEqualTo(-1);
        store.deleteAll("google:owner");
    }
}
