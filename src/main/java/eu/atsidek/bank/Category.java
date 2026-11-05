package eu.atsidek.bank;

public enum Category {
    BUSINESS_INCOME("Veiklos pajamos"),
    INTERNAL_TRANSFER("Vidinis pervedimas"),
    PERSONAL("Asmeninė (dovana, grąžinimas, paskola)"),
    SALARY("Darbo užmokestis"),
    UNKNOWN("Nenustatyta");

    private final String label;

    Category(String label) { this.label = label; }

    public String label() { return label; }
}
