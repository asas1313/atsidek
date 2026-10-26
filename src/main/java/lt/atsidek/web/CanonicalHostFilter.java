package lt.atsidek.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Nukreipia kitus hostname (pvz. www.) į pagrindinį domeną 301 kodu. Išjungta, kai host nenurodytas. */
@Component
public class CanonicalHostFilter extends OncePerRequestFilter {

    private final String canonicalHost;

    public CanonicalHostFilter(@Value("${atsidek.canonical-host:}") String canonicalHost) {
        this.canonicalHost = canonicalHost;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getServerName();
        if (!canonicalHost.isBlank() && host != null && host.startsWith("www.") && !host.equals(canonicalHost)) {
            String query = request.getQueryString();
            response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
            response.setHeader(HttpHeaders.LOCATION,
                    "https://" + canonicalHost + request.getRequestURI() + (query == null ? "" : "?" + query));
            return;
        }
        chain.doFilter(request, response);
    }
}
