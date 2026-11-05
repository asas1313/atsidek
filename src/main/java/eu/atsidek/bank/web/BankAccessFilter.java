package eu.atsidek.bank.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Banko duomenų ekranai: išjungti, kol nepaleista ATSIDEK_BANK_ENABLED=true (tada 404); jei nurodytas
 * ATSIDEK_BANK_PASSWORD, reikia HTTP Basic prisijungimo. Tai laikina apsauga, kol nėra paskyrų.
 */
@Component
public class BankAccessFilter extends OncePerRequestFilter {

    static final List<String> PREFIXES = List.of("/importas", "/importai", "/suvestine", "/taisykles", "/nustatymai");

    private final boolean enabled;
    private final String password;

    public BankAccessFilter(@Value("${atsidek.bank.enabled:false}") boolean enabled,
                            @Value("${atsidek.bank.password:}") String password) {
        this.enabled = enabled;
        this.password = password;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return PREFIXES.stream().noneMatch(x -> p.equals(x) || p.startsWith(x + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            res.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (!password.isEmpty() && !authorized(req.getHeader("Authorization"))) {
            res.setHeader("WWW-Authenticate", "Basic realm=\"Atsidek\", charset=\"UTF-8\"");
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        res.setHeader("Cache-Control", "no-store");
        chain.doFilter(req, res);
    }

    private boolean authorized(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) return false;
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
            String given = decoded.substring(decoded.indexOf(':') + 1);
            return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
