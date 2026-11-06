package eu.atsidek.bank;

import eu.atsidek.bank.store.BankStore;
import eu.atsidek.engine.TaxEngine;
import eu.atsidek.engine.TaxInput;
import eu.atsidek.engine.TaxRules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Patvirtintas pajamas paduoda esamam skaičiavimo varikliui (mokesčių logika lieka vienoje vietoje).
 * Mėnesio suma = metinė prognozė iki mėnesio pabaigos − prognozė iki ankstesnio mėnesio pabaigos.
 * Minimalus PSD kaupiasi tik mėnesiais, kuriuos vartotojas nurodė nustatymuose (kitais PSD moka darbdavys).
 */
@Service
public class SummaryService {

    public record Month(int month, BigDecimal business, BigDecimal salary, BigDecimal ytdBusiness,
                        BigDecimal ytdSalary, BigDecimal ytdTax, BigDecimal setAside) {
        /** Mėnuo rodomas, jei buvo pajamų arba reikia atidėti minimalų PSD (net ir be jokių kitų pajamų). */
        public boolean hasData() { return business.signum() > 0 || salary.signum() > 0 || setAside.signum() > 0; }
    }

    public record Summary(int year, List<Month> months, Month headline, BigDecimal ytdIncome,
                          BigDecimal bracket1, BigDecimal bracket2) {}

    private final BankStore store;
    private final CurrentUser currentUser;
    private final TaxEngine engine = TaxEngine.forYear2026();
    private final TaxRules rules = TaxRules.RULES_2026;

    public SummaryService(BankStore store, CurrentUser currentUser) {
        this.store = store;
        this.currentUser = currentUser;
    }

    /** Metinis mokesčių įvertis (GPM + Sodra) pagal geresnį iš dviejų išlaidų metodų. */
    BigDecimal estimate(BigDecimal business, BigDecimal salary, int psdMonths) {
        TaxInput in = new TaxInput(business, null, BigDecimal.ZERO, salary, false, psdMonths);
        return engine.compare(in).better().total();
    }

    public Summary summary(int year) { return summary(year, LocalDate.now()); }

    /** Rodoma „iki šiol“: iki einamojo mėnesio (ar paskutinio mėnesio su pajamomis, jei jis vėlesnis). */
    public Summary summary(int year, LocalDate today) {
        List<BankStore.MonthSum> sums = store.confirmedMonthlySums(currentUser.id(), year);
        int psdFrom = psdFrom(year), psdTo = psdTo(year);
        List<Month> months = new ArrayList<>();
        BigDecimal ytdB = BigDecimal.ZERO, ytdS = BigDecimal.ZERO, prevTax = BigDecimal.ZERO;
        Month headline = null;
        for (int m = 1; m <= 12; m++) {
            final int mm = m;
            BigDecimal b = sum(sums, mm, Category.BUSINESS_INCOME), s = sum(sums, mm, Category.SALARY);
            ytdB = ytdB.add(b);
            ytdS = ytdS.add(s);
            int psdMonths = Math.max(0, Math.min(m, psdTo) - psdFrom + 1);
            // Kol dar nėra jokių pajamų, variklis PSD nemoka – bet minimalus PSD už pasirinktus mėnesius vis tiek atidedamas.
            BigDecimal tax = ytdB.signum() == 0 && ytdS.signum() == 0
                    ? rules.minMonthlyPsd().multiply(BigDecimal.valueOf(psdMonths))
                    : estimate(ytdB, ytdS, psdMonths);
            Month row = new Month(m, b, s, ytdB, ytdS, tax, tax.subtract(prevTax).setScale(2, RoundingMode.HALF_UP));
            months.add(row);
            if (row.hasData()) headline = row;
            prevTax = tax;
        }
        // Rodome „iki šiol“: iki einamojo mėnesio (praėjusiems metams – iki gruodžio), ateities mėnesių nerodome.
        int last = year < today.getYear() ? 12 : year == today.getYear() ? today.getMonthValue() : 0;
        for (Month x : months) if (x.business().signum() > 0 || x.salary().signum() > 0) last = Math.max(last, x.month());
        for (int i = last; i < months.size(); i++) {
            Month x = months.get(i);
            months.set(i, new Month(x.month(), x.business(), x.salary(), x.ytdBusiness(), x.ytdSalary(), x.ytdTax(), BigDecimal.ZERO));
        }
        headline = last == 0 ? null : months.get(last - 1);
        BigDecimal vdu = rules.vdu();
        return new Summary(year, months, headline, ytdB.add(ytdS),
                rules.bracket1Vdu().multiply(vdu), rules.bracket2Vdu().multiply(vdu));
    }

    /** PSD mėnesių nustatymai galioja konkretiems metams; nenurodžius – visi 12 mėnesių. */
    public int psdFrom(int year) {
        return store.intSetting(currentUser.id(), "psd_from_" + year, 1);
    }

    public int psdTo(int year) {
        return store.intSetting(currentUser.id(), "psd_to_" + year, 12);
    }

    private static BigDecimal sum(List<BankStore.MonthSum> sums, int month, Category c) {
        return sums.stream().filter(x -> x.month() == month && x.category() == c)
                .map(BankStore.MonthSum::sum).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
