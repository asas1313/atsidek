package eu.atsidek.bank.parse;

import java.io.IOException;
import java.io.InputStream;

public interface StatementParser {
    /** Formato atpažinimas pagal pirmus ~4 KB. */
    boolean supports(byte[] head, String fileName);

    ParseResult parse(InputStream in) throws IOException;
}
