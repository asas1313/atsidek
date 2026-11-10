package eu.atsidek.auth;

import eu.atsidek.entitlement.AppUserStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sesijos atsparumas, kai {@code app_user} eilutė dingsta sesijos metu (PLAN.md §6 task 15,
 * blokuojantis, naudotojo reikalaujamas pataisymas). Jei vartotojas ištrina paskyrą viename
 * naršyklės lange ({@code AccountController}, task 13), kito lango/sesijos {@code SecurityContext}
 * vis tiek turi galiojantį {@code AppOidcUser} principal'ą, rodantį į jau neegzistuojantį
 * {@code app_user.id} - valdikliai, kurie pasitiki, kad šis id egzistuoja, priešingu atveju gautų
 * netikėtą klaidą arba nenuoseklų atsakymą. Šis filtras tai pagauna PRIEŠ užklausai pasiekiant
 * valdiklį: jei principal'as yra {@link AppOidcUser}, kurio {@code app_user.id} DB nebėra, baigia
 * {@code HttpSession} ir nukreipia į {@code "/"}.
 *
 * <p>Registruojamas {@code SecurityConfig}'e PO {@code AuthorizationFilter} (t.y. PO autentifikacijos
 * ir autorizacijos sprendimo), bet PRIEŠ užklausai pasiekiant {@code DispatcherServlet}/valdiklį.
 * Specialiai ne {@code @Component}, kaip ir {@code ApiKeyFilter} - registruojamas tik saugumo
 * grandinėje, ne kaip bendras servlet filtras.
 */
public class MissingAppUserFilter extends OncePerRequestFilter {

    private final AppUserStore appUserStore;

    public MissingAppUserFilter(AppUserStore appUserStore) {
        this.appUserStore = appUserStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AppOidcUser user
                && appUserStore.findById(user.appUserId()).isEmpty()) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            response.sendRedirect(request.getContextPath() + "/");
            return;
        }
        chain.doFilter(request, response);
    }
}
