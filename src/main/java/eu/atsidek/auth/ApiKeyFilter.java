package eu.atsidek.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Saugo {@code /api/**} (AI ir kitų programų JSON API) paprastu API raktu. Raktas siunčiamas antraštėje
 * {@code X-API-Key} arba {@code Authorization: Bearer <raktas>}. Serveryje saugomos tik raktų SHA-256 maišos
 * (hex), todėl nutekėjus konfigūracijai raktų atkurti neįmanoma. Jei maišų nenurodyta – API uždarytas.
 * <p>
 * Specialiai ne {@code @Component}: registruojamas tik saugumo grandinėje, ne kaip bendras servlet filtras.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    private final List<byte[]> hashes;

    public ApiKeyFilter(List<String> hexHashes) {
        this.hashes = hexHashes.stream().map(h -> HexFormat.of().parseHex(h)).toList();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String key = extractKey(req);
        if (key != null && matches(key)) {
            chain.doFilter(req, res);
            return;
        }
        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        res.setHeader("WWW-Authenticate", "Bearer");
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"error\":\"Trūksta arba neteisingas API raktas\"}");
    }

    private static String extractKey(HttpServletRequest req) {
        String k = req.getHeader("X-API-Key");
        if (k == null || k.isBlank()) {
            String auth = req.getHeader("Authorization");
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                k = auth.substring(7);
            }
        }
        return k == null || k.isBlank() ? null : k.trim();
    }

    private boolean matches(String key) {
        byte[] given = sha256(key);
        boolean ok = false;
        for (byte[] h : hashes) {
            ok |= MessageDigest.isEqual(h, given); // be ankstyvo nutraukimo
        }
        return ok;
    }

    public static String hash(String key) {
        return HexFormat.of().formatHex(sha256(key));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
