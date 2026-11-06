package eu.atsidek.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.atsidek.bank.store.BankStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
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
    void navShowsLoginWhenAnonymousAndLogoutWhenSignedIn() throws Exception {
        String anon = mvc.perform(get("/skaiciuokle")).andReturn().getResponse().getContentAsString();
        assertThat(anon).contains("Prisijungti").doesNotContain("Atsijungti").doesNotContain("/importas");
        String in = mvc.perform(get("/skaiciuokle").with(oidcLogin())).andReturn().getResponse().getContentAsString();
        assertThat(in).contains("Atsijungti").contains("/importas").doesNotContain(">Prisijungti<");
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
