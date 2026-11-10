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
 * Viešos: skaičiuoklė, straipsnis, privatumas, statiniai failai. JSON API ({@code /api/**}) – tik su API raktu.
 * Visa kita (banko ekranai) – tik su Google prisijungimu. Neprisijungęs vartotojas nukreipiamas tiesiai į Google.
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
                        .requestMatchers("/", "/skaiciuokle", "/calculate", "/privatumas", "/straipsniai/**", "/api/**",
                                "/goals/**", "/taupymo-tikslai",
                                "/style.css", "/htmx.min.js", "/robots.txt", "/sitemap.xml", "/favicon.ico", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(o -> o.authorizationEndpoint(a -> a.authorizationRequestResolver(resolver)))
                .logout(l -> l.logoutSuccessUrl("/").invalidateHttpSession(true).deleteCookies("JSESSIONID"))
                // Viešiems POST be sesijos (htmx skaičiuoklė, JSON API) CSRF žetono nėra
                .csrf(c -> c.ignoringRequestMatchers("/calculate", "/api/**", "/goals/**"));
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
