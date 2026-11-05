package eu.atsidek.bank.parse;

import eu.atsidek.bank.Direction;
import eu.atsidek.bank.NormalizedTransaction;
import eu.atsidek.bank.Text;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

/**
 * ISO 20022 camt.053 (versijos .02 ir .08; kitos .0x taip pat skaitomos, nes skiriasi tik šalių apgaubimas).
 * Srautinė (StAX) analizė, DTD ir išorinės esybės išjungtos (XXE).
 */
@Component
public class Camt053Parser implements StatementParser {

    private static final Pattern NS = Pattern.compile("camt\\.053\\.001\\.(\\d{2})");

    private static XMLInputFactory newFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        return f;
    }

    @Override
    public boolean supports(byte[] head, String fileName) {
        return NS.matcher(new String(head, StandardCharsets.ISO_8859_1)).find();
    }

    private static final class Tx {
        String amt, ccy, e2e, dbtrName, cdtrName, dbtrIban, cdtrIban, structured;
        final List<String> ustrd = new ArrayList<>();
    }

    private static final class Entry {
        int line;
        String amt, ccy, cdtDbt, sts, date, ref;
        final List<Tx> txs = new ArrayList<>();
    }

    @Override
    public ParseResult parse(InputStream in) throws IOException {
        List<NormalizedTransaction> out = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        int[] counters = new int[2]; // 0 = praleista, 1 = viso įrašų
        String version = "02";
        try {
            XMLStreamReader r = newFactory().createXMLStreamReader(in);
            List<String> stack = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            String iban = null;
            String ccyAttr = null;
            Entry cur = null;
            Tx tx = null;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.DTD) {
                    throw new StatementException(StatementException.Kind.UNSUPPORTED_FORMAT,
                            "Failas turi nepriimtinų XML konstrukcijų (DTD) ir buvo atmestas.");
                }
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    if (stack.isEmpty()) {
                        Matcher m = NS.matcher(String.valueOf(r.getNamespaceURI()));
                        if (m.find()) version = m.group(1);
                    }
                    stack.add(name);
                    text.setLength(0);
                    if ("Amt".equals(name)) ccyAttr = r.getAttributeValue(null, "Ccy");
                    if ("Ntry".equals(name) && stack.size() >= 2 && "Stmt".equals(stack.get(stack.size() - 2))) {
                        cur = new Entry();
                        cur.line = r.getLocation().getLineNumber();
                    } else if ("TxDtls".equals(name) && cur != null) {
                        tx = new Tx();
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS || ev == XMLStreamConstants.CDATA) {
                    text.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String name = stack.get(stack.size() - 1);
                    String p = String.join("/", stack);
                    String val = text.toString().trim();
                    if (cur == null) {
                        if (p.endsWith("Stmt/Acct/Id/IBAN")) iban = Text.normalizeIban(val);
                    } else if (tx != null) {
                        int i = p.lastIndexOf("/TxDtls/");
                        if (i >= 0) apply(tx, p.substring(i + 8), val, ccyAttr);
                        if ("TxDtls".equals(name)) {
                            cur.txs.add(tx);
                            tx = null;
                        }
                    } else if ("Ntry".equals(name) && stack.size() >= 2 && "Stmt".equals(stack.get(stack.size() - 2))) {
                        counters[1]++;
                        emit(cur, iban, "camt053." + version, out, errors, counters);
                        cur = null;
                    } else {
                        int i = p.lastIndexOf("/Ntry/");
                        if (i >= 0) {
                            switch (p.substring(i + 6)) {
                                case "Amt" -> { cur.amt = val; cur.ccy = ccyAttr; }
                                case "CdtDbtInd" -> cur.cdtDbt = val;
                                case "Sts", "Sts/Cd" -> { if (!val.isEmpty()) cur.sts = val; }
                                case "BookgDt/Dt", "BookgDt/DtTm" -> cur.date = val;
                                case "AcctSvcrRef" -> cur.ref = Text.blankToNull(val);
                                default -> { }
                            }
                        }
                    }
                    stack.remove(stack.size() - 1);
                    text.setLength(0);
                }
            }
            r.close();
        } catch (XMLStreamException e) {
            throw new StatementException(StatementException.Kind.UNSUPPORTED_FORMAT,
                    "Nepavyko perskaityti XML failo. Patikrink, ar tai nepakeistas banko eksportas.");
        }
        return new ParseResult("camt053." + version, null, out, errors, counters[0], counters[1]);
    }

    private static void apply(Tx tx, String rel, String val, String ccy) {
        switch (rel) {
            case "Amt", "AmtDtls/TxAmt/Amt" -> { tx.amt = val; tx.ccy = ccy; }
            case "Refs/EndToEndId" -> tx.e2e = "NOTPROVIDED".equalsIgnoreCase(val) ? null : Text.blankToNull(val);
            case "RltdPties/Dbtr/Nm", "RltdPties/Dbtr/Pty/Nm" -> tx.dbtrName = Text.blankToNull(val);
            case "RltdPties/Cdtr/Nm", "RltdPties/Cdtr/Pty/Nm" -> tx.cdtrName = Text.blankToNull(val);
            case "RltdPties/DbtrAcct/Id/IBAN" -> tx.dbtrIban = Text.normalizeIban(val);
            case "RltdPties/CdtrAcct/Id/IBAN" -> tx.cdtrIban = Text.normalizeIban(val);
            case "RmtInf/Ustrd" -> { if (!val.isEmpty()) tx.ustrd.add(val); }
            case "RmtInf/Strd/CdtrRefInf/Ref" -> tx.structured = Text.blankToNull(val);
            default -> { }
        }
    }

    private static void emit(Entry e, String iban, String format, List<NormalizedTransaction> out,
                             List<RowError> errors, int[] counters) {
        if (e.sts != null && !"BOOK".equalsIgnoreCase(e.sts)) {
            counters[0]++;
            return;
        }
        try {
            if (iban == null) throw new IllegalArgumentException("Išraše nerasta sąskaitos IBAN");
            Direction dir = switch (String.valueOf(e.cdtDbt)) {
                case "CRDT" -> Direction.IN;
                case "DBIT" -> Direction.OUT;
                default -> throw new IllegalArgumentException("Nežinoma operacijos kryptis");
            };
            LocalDate date = Text.parseDate(e.date);
            List<Tx> txs = e.txs.isEmpty() ? List.of(new Tx()) : e.txs;
            boolean batch = txs.size() > 1;
            for (Tx t : txs) {
                String amtRaw = t.amt != null ? t.amt : e.amt;
                String ccy = t.amt != null ? t.ccy : e.ccy;
                BigDecimal amount = Text.parseAmount(amtRaw).abs();
                if (!"EUR".equalsIgnoreCase(ccy)) {
                    errors.add(new RowError(e.line, "Palaikomos tik EUR operacijos (rasta " + ccy + ")"));
                    continue;
                }
                boolean in = dir == Direction.IN;
                out.add(new NormalizedTransaction(iban, date, amount, "EUR", dir,
                        in ? t.dbtrName : t.cdtrName, in ? t.dbtrIban : t.cdtrIban,
                        t.ustrd.isEmpty() ? null : String.join(" ", t.ustrd), t.structured,
                        batch ? null : e.ref, t.e2e, format));
            }
        } catch (IllegalArgumentException ex) {
            errors.add(new RowError(e.line, ex.getMessage()));
        }
    }
}
