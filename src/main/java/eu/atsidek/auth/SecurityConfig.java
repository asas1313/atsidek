package eu.atsidek.auth;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
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
                                "/style.css", "/htmx.min.js", "/robots.txt", "/sitemap.xml", "/favicon.ico", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(o -> o.authorizationEndpoint(a -> a.authorizationRequestResolver(resolver)))
                .logout(l -> l.logoutSuccessUrl("/").invalidateHttpSession(true).deleteCookies("JSESSIONID"))
                // Viešiems POST be sesijos (htmx skaičiuoklė, JSON API) CSRF žetono nėra
                .csrf(c -> c.ignoringRequestMatchers("/calculate", "/api/**"));
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

    /** Priima tik patvirtintą el. paštą ir (jei sąrašas nurodytas) tik leidžiamus adresus. */
    @Bean
    OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService(
            @Value("${atsidek.auth.allowed-emails:}") String allowed,
            @Value("${atsidek.auth.owner-email:}") String owner) {
        Set<String> list = Arrays.stream((allowed + "," + owner).split(","))
                .map(s -> s.trim().toLowerCase()).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        boolean restricted = !allowed.isBlank();
        OidcUserService delegate = new OidcUserService();
        return request -> {
            OidcUser user = delegate.loadUser(request);
            String email = user.getEmail() == null ? "" : user.getEmail().toLowerCase();
            boolean verified = Boolean.TRUE.equals(user.getEmailVerified());
            if (!verified || (restricted && !list.contains(email))) {
                throw new OAuth2AuthenticationException(new OAuth2Error("access_denied"), "El. paštas neleidžiamas");
            }
            return user;
        };
    }
}
