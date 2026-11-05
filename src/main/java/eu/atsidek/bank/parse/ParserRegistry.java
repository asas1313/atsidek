package eu.atsidek.bank.parse;

import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ParserRegistry {

    private final List<StatementParser> parsers;

    public ParserRegistry(List<StatementParser> parsers) {
        this.parsers = parsers;
    }

    public StatementParser find(byte[] data, String fileName) {
        byte[] head = Arrays.copyOf(data, Math.min(data.length, 4096));
        return parsers.stream().filter(x -> x.supports(head, fileName)).findFirst()
                .orElseThrow(() -> new StatementException(StatementException.Kind.UNSUPPORTED_FORMAT,
                        "Šio formato kol kas neatpažįstame. Palaikome ISO 20022 (camt.053) XML ir Revolut CSV. "
                                + "Parašyk, kokio banko išrašą bandei įkelti, ir pridėsime."));
    }
}
