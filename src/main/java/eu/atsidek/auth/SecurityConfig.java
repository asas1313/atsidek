package eu.atsidek.auth;

import eu.atsidek.entitlement.AppUserStore;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Default-deny (žr. ADR-0001, plano §6 task 6): {@code anyRequest().hasRole("ADMIN")} yra galutinis
 * atsarginis variantas - naujas, sąmoningai neklasifikuotas kelias yra admin-only, ne tyliai viešas.
 * Viešos: skaičiuoklė, taupymo tikslų nemokama dalis, straipsniai, statiniai failai. JSON API
 * ({@code /api/**}) – tik su API raktu (ApiKeyFilter). ROLE_USER – taupymo tikslo sekimas, paskyra.
 * ROLE_ADMIN (leidžiamų/savininko el. paštai) – banko modulis, nepakitusi apimtis.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, OAuth2AuthorizationRequestResolver resolver,
                                    @Value("${atsidek.api.key-hashes:}") String apiKeyHashes) throws Exception {
        List<String> hashes = Arrays.stream(apiKeyHashes.split(","))
                .map(s -> s.trim().toLowerCase()).filter(s -> !s.isEmpty()).toList();
        http
                // /api/** apsaugotas API raktu (ne Google): žr. ApiKeyFilter
                .addFilterBefore(new ApiKeyFilter(hashes), AuthorizationFilter.class)
                .authorizeHttpRequests(a -> a
                        // Viešos: skaičiuoklė, taupymo tikslų nemokama dalis, statiniai failai.
                        .requestMatchers("/", "/skaiciuokle", "/calculate", "/privatumas",
                                "/style.css", "/htmx.min.js", "/robots.txt", "/sitemap.xml", "/favicon.ico", "/error",
                                "/taupymo-tikslai", "/goals/calculate", "/goals/stats/defaults",
                                "/goals/tikslai/preliminarus").permitAll()
                        // Dvi siauros, atskirai pagrįstos šablonu apsaugotos išimtys:
                        // /api/** – jau apsaugota API raktu (ApiKeyFilter, veikia PRIEŠ AuthorizationFilter),
                        //           permitAll() čia reiškia tik "Spring Security pati nebeprideda rolės reikalavimo";
                        // /straipsniai/** – atviri statiniai straipsniai, be jautrių duomenų.
                        .requestMatchers("/api/**", "/straipsniai/**").permitAll()
                        // Prisijungęs (ROLE_USER) – taupymo tikslo sekimas, paskyra.
                        // /goals/tikslai/laukiantis/atmesti pridėtas task 11 metu (plano §6 task 6
                        // tekste dar nebuvo šio konkretaus kelio, nes jo pavadinimas buvo nustatytas
                        // vėliau, task 11) - whitelist testas (task 7) tiksliai tam ir sukurtas, kad
                        // pagautų tokį pamirštą kelią; čia jis priskiriamas ROLE_USER, kaip ir kiti
                        // /goals/tikslai/** keliai, ne default-deny ROLE_ADMIN atsarginiam variantui.
                        .requestMatchers("/mano-tikslas", "/goals/tikslai", "/goals/tikslai/*",
                                "/goals/tikslai/*/irasai", "/goals/tikslai/laukiantis/atmesti",
                                "/paskyra", "/paskyra/istrinti").hasRole("USER")
                        // Banko modulis – tik ROLE_ADMIN (leidžiamų/savininko el. paštų sąrašas).
                        // Išvardinta explicitiškai, kad task 7 route-table whitelist testas turėtų
                        // ką konkretaus patikrinti ir šiam moduliui (nors anyRequest() fallback tą pačią
                        // taisyklę ir taip taikytų).
                        .requestMatchers("/importas", "/suvestine", "/taisykles", "/nustatymai").hasRole("ADMIN")
                        // Default-deny: kiekvienas naujas, sąmoningai nepriskirtas kelias yra admin-only
                        // (fail closed), ne tyliai viešas ir ne tyliai sugadintas visiems.
                        .anyRequest().hasRole("ADMIN"))
                .oauth2Login(o -> o.authorizationEndpoint(a -> a.authorizationRequestResolver(resolver)))
                .logout(l -> l.logoutSuccessUrl("/").invalidateHttpSession(true).deleteCookies("JSESSIONID"))
                // Tik genuinai viešiems POST be sesijos (htmx skaičiuoklė, JSON API) CSRF žetono nereikia.
                // /goals/tikslai/preliminarus SĄMONINGAI NĖRA šiame sąraše: jis permitAll() autorizacijai
                // (gali kviesti bet kas), bet vis tiek reikalauja CSRF žetono kaip ir kiekvienas kitas
                // būseną keičiantis POST – kitaip svetima svetainė galėtų nutaikytai siųsti suklastotą
                // skaičiavimą į aukos sesiją (žr. ADR-0001).
                .csrf(c -> c.ignoringRequestMatchers("/calculate", "/api/**", "/goals/calculate"));
        return http.build();
    }

    /** Google visada rodo paskyros pasirinkimą, kad po atsijungimo būtų galima prisijungti su kita paskyra. */
    @Bean
    OAuth2AuthorizationRequestResolver authorizationRequestResolver(ClientRegistrationRepository clients) {
        DefaultOAuth2AuthorizationRequestResolver resolver =
                new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(c -> c.additionalParameters(p -> p.put("prompt", "select_account")));
        return resolver;
    }

    /**
     * Priima kiekvieną patvirtintą (email_verified) Google prisijungimą, provisionuoja {@code app_user}
     * eilutę ir priskiria ROLE_USER visiems, papildomai ROLE_ADMIN leidžiamų/savininko el. paštams
     * (žr. ADR-0001, AppOidcUserService). Bank modulio apsauga nuo šiol priklauso nuo ROLE_ADMIN, ne nuo
     * atmesto prisijungimo - žr. plano §6 task 6 (default-deny SecurityConfig), kuris pakeičia
     * authorizeHttpRequests taisykles, kad šis bean'o pakeitimas nepaliktų banko ekranų atvirų.
     */
    @Bean
    OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService(
            AppUserStore appUserStore,
            @Value("${atsidek.auth.allowed-emails:}") String allowed,
            @Value("${atsidek.auth.owner-email:}") String owner) {
        return new AppOidcUserService(new OidcUserService(), appUserStore, allowed, owner);
    }
}
