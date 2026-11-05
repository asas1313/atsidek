package eu.atsidek.bank;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;

/** Smulkūs bendri įrankiai: teksto normalizavimas, skaičių ir datų skaitymas, maišos. */
public final class Text {
    private Text() {}

    /** Mažosios raidės, be diakritikų ("Sąskaita" = "saskaita"), sutrauktos tarpų sekos. */
    public static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[\\s\\u00a0]+", " ").trim();
    }

    public static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public static String normalizeIban(String iban) {
        return iban == null ? null : blankToNull(iban.replaceAll("\\s+", "").toUpperCase(Locale.ROOT));
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Priima yyyy-MM-dd ir dd.MM.yyyy, galimas laiko priedas. */
    public static LocalDate parseDate(String raw) {
        if (raw == null) throw new IllegalArgumentException("Nėra datos");
        String s = raw.trim();
        try {
            if (s.matches("\\d{4}-\\d{2}-\\d{2}.*")) return LocalDate.parse(s.substring(0, 10));
            if (s.matches("\\d{2}\\.\\d{2}\\.\\d{4}.*")) {
                return LocalDate.of(Integer.parseInt(s.substring(6, 10)), Integer.parseInt(s.substring(3, 5)),
                        Integer.parseInt(s.substring(0, 2)));
            }
        } catch (DateTimeException ignored) {
            // žemiau bendra klaida
        }
        throw new IllegalArgumentException("Neatpažinta data: " + s);
    }

    /** Priima "1 234,56", "1,234.56", "-12,5", "12.50". */
    public static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Nėra sumos");
        String s = raw.trim().replaceAll("[\\s\\u00a0']", "").replace("€", "").replace("EUR", "");
        int comma = s.lastIndexOf(','), dot = s.lastIndexOf('.');
        if (comma >= 0 && dot >= 0) {
            s = comma > dot ? s.replace(".", "").replace(',', '.') : s.replace(",", "");
        } else if (comma >= 0) {
            s = s.replace(',', '.');
        }
        try {
            return new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Neatpažinta suma: " + raw.trim());
        }
    }
}
