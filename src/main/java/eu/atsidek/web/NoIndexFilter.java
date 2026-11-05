package eu.atsidek.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Staging aplinkai: neleidžia paieškos sistemoms indeksuoti (įjungiama ATSIDEK_NOINDEX=true). */
@Component
@ConditionalOnProperty(name = "atsidek.noindex", havingValue = "true")
public class NoIndexFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        chain.doFilter(request, response);
    }
}
