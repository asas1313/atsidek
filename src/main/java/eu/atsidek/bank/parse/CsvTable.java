package eu.atsidek.bank.parse;

import eu.atsidek.bank.Text;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Mažas CSV skaitytuvas: kodavimas (UTF-8 / Windows-1257), skyriklis, kabutės. */
public final class CsvTable {

    public static final int MAX_ROWS = 50_000;
    private static final Charset WIN_1257 = Charset.forName("windows-1257");

    public record Row(int line, List<String> cells) {}

    private final List<Row> rows;
    private final Map<String, Integer> index = new HashMap<>();

    private CsvTable(List<String> header, List<Row> rows) {
        this.rows = rows;
        for (int i = 0; i < header.size(); i++) index.putIfAbsent(Text.normalize(header.get(i)), i);
    }

    public List<Row> rows() { return rows; }

    /** Pirma netuščia reikšmė iš nurodytų (normalizuotų) stulpelių. */
    public String get(Row row, String... columns) {
        for (String c : columns) {
            Integer i = index.get(c);
            if (i != null && i < row.cells().size()) {
                String v = Text.blankToNull(row.cells().get(i));
                if (v != null) return v;
            }
        }
        return null;
    }

    /** Tik antraštė (formato atpažinimui iš pirmų baitų). */
    public static List<String> headerOf(byte[] head) {
        String text = decode(head, true);
        int nl = text.indexOf('\n');
        String first = nl >= 0 ? text.substring(0, nl) : text;
        return splitLine(first, detectDelimiter(first));
    }

    public static CsvTable parse(byte[] data) {
        String text = decode(data, false);
        int nl = text.indexOf('\n');
        char delim = detectDelimiter(nl >= 0 ? text.substring(0, nl) : text);
        List<List<String>> records = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        List<String> cur = new ArrayList<>();
        boolean quoted = false, any = false;
        int line = 1, startLine = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else quoted = false;
                } else {
                    if (c == '\n') line++;
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
                any = true;
            } else if (c == delim) {
                cur.add(cell.toString());
                cell.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                if (any || cell.length() > 0) {
                    cur.add(cell.toString());
                    records.add(cur);
                    lines.add(startLine);
                    if (records.size() > MAX_ROWS + 1) throw tooMany();
                }
                cur = new ArrayList<>();
                cell.setLength(0);
                any = false;
                line++;
                startLine = line;
            } else {
                cell.append(c);
                any = true;
            }
        }
        if (any || cell.length() > 0) {
            cur.add(cell.toString());
            records.add(cur);
            lines.add(startLine);
        }
        if (records.isEmpty()) {
            throw new StatementException(StatementException.Kind.UNSUPPORTED_FORMAT, "Failas tuščias.");
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) rows.add(new Row(lines.get(i), records.get(i)));
        return new CsvTable(records.get(0).stream().map(String::trim).toList(), rows);
    }

    private static StatementException tooMany() {
        return new StatementException(StatementException.Kind.LIMIT,
                "Faile per daug eilučių (daugiau nei " + MAX_ROWS + "). Įkelk trumpesnį laikotarpį.");
    }

    static String decode(byte[] data, boolean lenientTail) {
        int off = data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF ? 3 : 0;
        ByteBuffer buf = ByteBuffer.wrap(data, off, data.length - off);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(buf).toString();
        } catch (CharacterCodingException e) {
            // pirmų baitų ribinis daugiabaitis simbolis nereiškia, kad tai ne UTF-8
            if (lenientTail) {
                return new String(data, off, data.length - off, StandardCharsets.UTF_8);
            }
            return new String(data, off, data.length - off, WIN_1257);
        }
    }

    static char detectDelimiter(String headerLine) {
        int semi = 0, comma = 0, tab = 0;
        boolean q = false;
        for (char c : headerLine.toCharArray()) {
            if (c == '"') q = !q;
            else if (!q) {
                if (c == ';') semi++;
                else if (c == ',') comma++;
                else if (c == '\t') tab++;
            }
        }
        if (semi >= comma && semi >= tab && semi > 0) return ';';
        if (tab > comma) return '\t';
        return ',';
    }

    static List<String> splitLine(String line, char delim) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else q = !q;
            } else if (c == delim && !q) {
                out.add(cell.toString().trim());
                cell.setLength(0);
            } else if (c != '\r') {
                cell.append(c);
            }
        }
        out.add(cell.toString().trim());
        return out;
    }
}
