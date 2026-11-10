package eu.atsidek.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import eu.atsidek.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Plano §6 task 7: maršrutų lentelės "whitelist" saugumo regresijos testas. Vaikšto per VISUS šiuo
 * metu registruotus Spring MVC maršrutus ({@link RequestMappingHandlerMapping}) ir kiekvienam jų
 * patikrina autorizacijos rezultatą - anonimiškai ir kaip paprastas ROLE_USER (ne ROLE_ADMIN) -
 * lygindamas su šio testo PAČIO rašytu, nuo {@link SecurityConfig} NEPRIKLAUSOMU, allow-list'u.
 * Du sąrašai turi sutapti patys, ne vien sutapti su savimi - tai gaudo "pridėjo maršrutą, pamiršo
 * apsaugoti" nuokrypį, ne vien tą pačią klaidą, pakartotą dukart.
 *
 * <p>CSRF žetonas pridedamas prie VISŲ probe'ų (anonimiškai ir ROLE_USER), kad CSRF filtras
 * (atskirai testuojamas {@code AuthTest}'e) neuždengtų galimos autorizacijos skylės - be žetono,
 * neteisingai permitAll() POST maršrutas vis tiek grįžtų 403 dėl CSRF, o ne dėl rolės, ir testas
 * nepastebėtų realios autorizacijos klaidos.
 *
 * <p>Kelio kintamieji (pvz. {@code /importai/{id}}) pakeičiami placeholder reikšme - tikrinamas
 * autorizacijos rezultatas, ne tai, ar grąžinamas 200 (maršrutas, kurį saugumas praleidžia, bet
 * kuris po to teisėtai grąžina 404 dėl neegzistuojančio id, šiam testui vis tiek yra "leistas").
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityRouteWhitelistTest {

    private enum Access { PUBLIC, USER, ADMIN }

    /**
     * Rankomis rašytas, nuo {@code SecurityConfig} nepriklausomas sąrašas. Raktas: {@code "METODAS
     * patternas"}, pvz. {@code "GET /importas"}.
     */
    private static final Map<String, Access> ALLOW_LIST = Map.ofEntries(
            // Viešos - skaičiuoklė, taupymo tikslų nemokama dalis, statika, klaidos puslapis.
            Map.entry("GET /", Access.PUBLIC),
            Map.entry("GET /skaiciuokle", Access.PUBLIC),
            Map.entry("POST /calculate", Access.PUBLIC),
            Map.entry("GET /straipsniai/30-proc-ar-faktines-islaidos", Access.PUBLIC),
            Map.entry("GET /privatumas", Access.PUBLIC),
            Map.entry("GET /taupymo-tikslai", Access.PUBLIC),
            Map.entry("GET /error", Access.PUBLIC),
            Map.entry("POST /goals/calculate", Access.PUBLIC),
            Map.entry("GET /goals/stats/defaults", Access.PUBLIC),

            // Banko modulis - tik ROLE_ADMIN (nepakitusi apimtis, žr. ADR-0001).
            Map.entry("GET /importas", Access.ADMIN),
            Map.entry("POST /importas", Access.ADMIN),
            Map.entry("GET /importai/{id}", Access.ADMIN),
            Map.entry("POST /importai/{id}/istrinti", Access.ADMIN),
            Map.entry("POST /importai/{id}/patvirtinti", Access.ADMIN),
            Map.entry("GET /suvestine", Access.ADMIN),
            Map.entry("GET /taisykles", Access.ADMIN),
            Map.entry("POST /taisykles", Access.ADMIN),
            Map.entry("POST /taisykles/{id}/istrinti", Access.ADMIN),
            Map.entry("GET /nustatymai", Access.ADMIN),
            Map.entry("POST /nustatymai/psd", Access.ADMIN),
            Map.entry("POST /nustatymai/istrinti-viska", Access.ADMIN)

            // ROLE_USER maršrutų (/mano-tikslas, /goals/tikslai/**, /paskyra/**) šiame sąraše dar
            // nėra - jų valdikliai (task 9-13) dar neegzistuoja, tad RequestMappingHandlerMapping
            // jų ir nepateikia; pridėti, kai bus registruoti.

            // /api/v1/** TYČIA NEIŠVARDINTAS - žr. SKIP_PREFIXES komentarą žemiau.
    );

    /**
     * {@code /api/**} yra permitAll() Spring Security rolių požiūriu TIK todėl, kad jį jau uždaro
     * atskiras, PRIEŠ AuthorizationFilter veikiantis {@link ApiKeyFilter} (žr. SecurityConfig,
     * ADR-0001) - testo aplinkoje (be sukonfigūruoto API rakto) jis VISADA atmeta su 401, nepaisant
     * autentifikacijos/rolės, tad šio testo HTTP-atsakymo heuristika čia tiesiog matuotų ApiKeyFilter,
     * ne Spring Security rolių sluoksnį, ir duotų klaidingą neigiamą rezultatą. ApiKeyFilter pats
     * turi atskirą, jau egzistuojantį testą ({@code TaxApiControllerTest.rejectsMissingOrWrongKey}
     * ir pan.) - čia sąmoningai praleidžiamas.
     */
    private static final List<String> SKIP_PREFIXES = List.of("/api/");

    @Autowired
    MockMvc mvc;

    @Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Test
    void everyRegisteredRouteMatchesItsHandWrittenAccessLevel() throws Exception {
        List<String> uncategorized = new ArrayList<>();
        List<String> checked = new ArrayList<>();

        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<String> patterns = info.getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            // Tušti methods (pvz. Spring Boot BasicErrorController /error) reiškia "bet kuris
            // metodas" - šio testo tikslams tikrinam realistišką GET.
            List<String> httpMethods = methods.isEmpty()
                    ? List.of("GET")
                    : methods.stream().map(Enum::name).toList();

            for (String pattern : patterns) {
                if (SKIP_PREFIXES.stream().anyMatch(pattern::startsWith)) {
                    continue;
                }
                for (String httpMethod : httpMethods) {
                    String key = httpMethod + " " + pattern;
                    Access expected = ALLOW_LIST.get(key);
                    if (expected == null) {
                        uncategorized.add(key);
                        continue;
                    }
                    checked.add(key);
                    String path = pattern.replaceAll("\\{[^/}]+}", "placeholder");
                    assertAccess(httpMethod, path, key, expected);
                }
            }
        }

        assertThat(uncategorized)
                .as("naujas maršrutas, kurio nėra šio testo allow-list'e - papildyti ALLOW_LIST arba "
                        + "SecurityConfig, kad abu sutaptų")
                .isEmpty();
        assertThat(checked).isNotEmpty();
    }

    private void assertAccess(String httpMethod, String path, String key, Access expected) throws Exception {
        boolean anonAllowed = isAllowed(httpMethod, path, false);
        boolean userAllowed = isAllowed(httpMethod, path, true);

        assertThat(anonAllowed).as(key + " (anonimiškai)").isEqualTo(expected == Access.PUBLIC);
        assertThat(userAllowed).as(key + " (ROLE_USER)")
                .isEqualTo(expected == Access.PUBLIC || expected == Access.USER);
    }

    /**
     * "Leista" = saugumo sluoksnis NEBUVO atsakymą nulėmusi priežastis (ne 401/403, ne nukreipimas į
     * Google prisijungimą). Jei, dėl placeholder reikšmės kelio kintamajame, valdiklis po saugumo
     * patikros pats sumeta išimtį (pvz. nerastas įrašas) - tai irgi "leista" šio testo tikslams: bet
     * kokia tikra autentifikacijos/autorizacijos atmetimo priežastis Spring Security filtrų grandinėje
     * paverčiama švariu 401/403/nukreipimu PRIEŠ pasiekiant valdiklį, tad neapgaubta išimtis savaime
     * reiškia, kad saugumo sluoksnis užklausą praleido.
     */
    private boolean isAllowed(String httpMethod, String path, boolean asUser) throws Exception {
        var builder = request(HttpMethod.valueOf(httpMethod), path).with(csrf());
        if (asUser) {
            builder = builder.with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_USER")));
        }
        MockHttpServletResponse response;
        try {
            response = mvc.perform(builder).andReturn().getResponse();
        } catch (Exception thrownAfterSecurityLayer) {
            return true;
        }
        int status = response.getStatus();
        if (status == 401 || status == 403) {
            return false;
        }
        String redirect = response.getRedirectedUrl();
        return redirect == null || !redirect.contains("/oauth2/authorization/google");
    }
}
