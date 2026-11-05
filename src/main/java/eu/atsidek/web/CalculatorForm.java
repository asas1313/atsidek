package eu.atsidek.web;

import java.math.BigDecimal;
import eu.atsidek.engine.ExpenseMethod;
import eu.atsidek.engine.TaxInput;

/** Formos laukai laikomi tekstu, kad „12 000,50“ ir panašūs įvedimai nesukeltų klaidos. */
public class CalculatorForm {

    private String revenue = "40000";
    private String method = "FLAT_30";
    private String actualExpenses = "";
    private String otherIncome = "";
    private boolean pillar2;
    private int psdFrom = 1;
    private int psdTo = 12;

    public TaxInput toInput() {
        ExpenseMethod m = "ACTUAL".equals(method) ? ExpenseMethod.ACTUAL : ExpenseMethod.FLAT_30;
        return new TaxInput(parse(revenue), m, parse(actualExpenses), parse(otherIncome), pillar2, psdMonths());
    }

    /** Mėnesių skaičius nuo–iki imtinai, kai taikomas minimalus PSD. */
    int psdMonths() {
        int from = Math.max(1, Math.min(12, psdFrom)), to = Math.max(1, Math.min(12, psdTo));
        return to >= from ? to - from + 1 : 0;
    }

    static BigDecimal parse(String s) {
        if (s == null) {
            return BigDecimal.ZERO;
        }
        String cleaned = s.replaceAll("[\\s\\u00A0€]", "").replace(',', '.');
        if (cleaned.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal v = new BigDecimal(cleaned);
            return v.signum() < 0 || v.compareTo(new BigDecimal("100000000")) > 0 ? BigDecimal.ZERO : v;
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    public String getRevenue() { return revenue; }
    public void setRevenue(String revenue) { this.revenue = revenue; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getActualExpenses() { return actualExpenses; }
    public void setActualExpenses(String actualExpenses) { this.actualExpenses = actualExpenses; }
    public String getOtherIncome() { return otherIncome; }
    public void setOtherIncome(String otherIncome) { this.otherIncome = otherIncome; }
    public int getPsdFrom() { return psdFrom; }
    public void setPsdFrom(int psdFrom) { this.psdFrom = psdFrom; }
    public int getPsdTo() { return psdTo; }
    public void setPsdTo(int psdTo) { this.psdTo = psdTo; }
    public boolean isPillar2() { return pillar2; }
    public void setPillar2(boolean pillar2) { this.pillar2 = pillar2; }
}
