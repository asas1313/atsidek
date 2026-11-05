package eu.atsidek.bank.web;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Sumos formatas „1 234,56 EUR“ (šablonuose: ${@money.eur(x)}). */
@Component("money")
public class Money {

    public String eur(BigDecimal v) {
        DecimalFormatSymbols s = new DecimalFormatSymbols(Locale.ROOT);
        s.setGroupingSeparator(' ');
        s.setDecimalSeparator(',');
        DecimalFormat f = new DecimalFormat("#,##0.00", s);
        return f.format(v == null ? BigDecimal.ZERO : v) + " EUR";
    }
}
