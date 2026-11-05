package eu.atsidek.bank.parse;

/** Klaida, kurios tekstas skirtas rodyti vartotojui. */
public class StatementException extends RuntimeException {
    public enum Kind { UNSUPPORTED_FORMAT, NO_TRANSACTIONS, TOO_LARGE, LIMIT }

    private final Kind kind;

    public StatementException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
