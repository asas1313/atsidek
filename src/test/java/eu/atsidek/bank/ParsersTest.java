package eu.atsidek.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.atsidek.bank.parse.Camt053Parser;
import eu.atsidek.bank.parse.ParseResult;
import eu.atsidek.bank.parse.ParserRegistry;
import eu.atsidek.bank.parse.RevolutCsvParser;
import eu.atsidek.bank.parse.StatementException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParsersTest {

    private final Camt053Parser camt = new Camt053Parser();
    private final RevolutCsvParser revolut = new RevolutCsvParser();
    private final ParserRegistry registry = new ParserRegistry(List.of(camt, revolut));

    private static byte[] resource(String name) throws IOException {
        return Files.readAllBytes(Path.of("src/test/resources/statements", name));
    }

    private ParseResult parse(byte[] data, String name) throws IOException {
        return registry.find(data, name).parse(new ByteArrayInputStream(data));
    }

    @Test
    void camt02ParsesEntriesBatchesAndSkips() throws IOException {
        ParseResult r = parse(resource("camt053/sample-02.xml"), "a.xml");
        assertThat(r.format()).isEqualTo("camt053.02");
        assertThat(r.transactions()).hasSize(5);
        assertThat(r.skipped()).isEqualTo(1);               // PDNG
        assertThat(r.errors()).hasSize(1);                  // USD
        NormalizedTransaction first = r.transactions().get(0);
        assertThat(first.accountIban()).isEqualTo("LT127300010123456789");
        assertThat(first.amount()).isEqualByComparingTo("1210.00");
        assertThat(first.direction()).isEqualTo(Direction.IN);
        assertThat(first.counterpartyName()).isEqualTo("UAB Pavyzdys");
        assertThat(first.counterpartyIban()).isEqualTo("LT997000000011112222");
        assertThat(first.endToEndId()).isNull();            // NOTPROVIDED
        assertThat(first.bankRef()).isEqualTo("REF-0001");
        assertThat(first.remittance()).contains("SF-123");
        NormalizedTransaction out = r.transactions().get(2);
        assertThat(out.direction()).isEqualTo(Direction.OUT);
        assertThat(out.counterpartyName()).isEqualTo("Parduotuvė");   // OUT: Cdtr
        NormalizedTransaction b1 = r.transactions().get(3), b2 = r.transactions().get(4);
        assertThat(b1.amount()).isEqualByComparingTo("100.00");
        assertThat(b2.amount()).isEqualByComparingTo("50.00");
        assertThat(b1.bankRef()).isNull();                   // paketinis įrašas: bendras AcctSvcrRef netinka raktui
        assertThat(b1.endToEndId()).isEqualTo("E2E-A");
    }

    @Test
    void camt08ParsesPartyWrapperAndStructuredRef() throws IOException {
        ParseResult r = parse(resource("camt053/sample-08.xml"), "b.xml");
        assertThat(r.format()).isEqualTo("camt053.08");
        assertThat(r.transactions()).hasSize(1);
        NormalizedTransaction t = r.transactions().get(0);
        assertThat(t.counterpartyName()).isEqualTo("Gmbh Kunde GmbH");
        assertThat(t.bookingDate()).hasToString("2026-11-03");
        assertThat(t.structuredRef()).isEqualTo("RF18539007547034");
    }

    @Test
    void xxeIsRejectedAndNeverReadsFiles() throws IOException {
        String evil = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/hostname">]>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02"><BkToCstmrStmt><Stmt>
                <Acct><Id><IBAN>LT127300010123456789</IBAN></Id></Acct>
                <Ntry><Amt Ccy="EUR">1.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts>BOOK</Sts>
                <BookgDt><Dt>2026-10-01</Dt></BookgDt>
                <NtryDtls><TxDtls><RltdPties><Dbtr><Nm>&xxe;</Nm></Dbtr></RltdPties></TxDtls></NtryDtls></Ntry>
                </Stmt></BkToCstmrStmt></Document>""";
        byte[] data = evil.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parse(data, "evil.xml")).isInstanceOf(StatementException.class);
    }

    @Test
    void revolutParsesCompletedEurOnly() throws IOException {
        ParseResult r = parse(resource("revolut/personal.csv"), "revolut.csv");
        assertThat(r.format()).isEqualTo("revolut-csv");
        assertThat(r.transactions()).hasSize(2);
        assertThat(r.skipped()).isEqualTo(1);               // PENDING
        assertThat(r.errors()).hasSize(2);                  // USD ir bloga data
        assertThat(r.errors().get(1).message()).contains("31/10/26");
        NormalizedTransaction in = r.transactions().get(0);
        assertThat(in.amount()).isEqualByComparingTo("1500.00");
        assertThat(in.direction()).isEqualTo(Direction.IN);
        assertThat(in.counterpartyName()).isEqualTo("UAB Klientas");
        NormalizedTransaction out = r.transactions().get(1);
        assertThat(out.amount()).isEqualByComparingTo("12.50");   // neigiama suma -> teigiama + OUT
        assertThat(out.direction()).isEqualTo(Direction.OUT);
    }

    @Test
    void windows1257SemicolonDecimalCommaCsv() throws IOException {
        String csv = "Type;Started Date;Completed Date;Description;Amount;Currency;State\n"
                + "TRANSFER;2026-10-02;31.10.2026;Payment from Ąžuolas Šiaulių UAB;1 234,56;EUR;COMPLETED\n";
        byte[] data = csv.getBytes(Charset.forName("windows-1257"));
        ParseResult r = parse(data, "x.csv");
        assertThat(r.errors()).isEmpty();
        assertThat(r.transactions()).hasSize(1);
        assertThat(r.transactions().get(0).counterpartyName()).isEqualTo("Ąžuolas Šiaulių UAB");
        assertThat(r.transactions().get(0).amount()).isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(r.transactions().get(0).bookingDate()).hasToString("2026-10-31");
    }

    @Test
    void utf8BomIsAccepted() throws IOException {
        String csv = "﻿Date completed (UTC),State,Description,Payer,Amount,Payment currency\n"
                + "2026-10-02,COMPLETED,Invoice 5,UAB Klientas,100.00,EUR\n";
        ParseResult r = parse(csv.getBytes(StandardCharsets.UTF_8), "business.csv");
        assertThat(r.transactions()).hasSize(1);
        assertThat(r.transactions().get(0).counterpartyName()).isEqualTo("UAB Klientas");
    }

    @Test
    void unknownFormatGivesLithuanianMessage() {
        byte[] data = "kažkas;kita\n1;2\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> registry.find(data, "x.csv"))
                .isInstanceOf(StatementException.class)
                .satisfies(e -> assertThat(((StatementException) e).kind()).isEqualTo(StatementException.Kind.UNSUPPORTED_FORMAT));
    }

    @Test
    void textHelpers() {
        assertThat(Text.normalize("  Sąskaita   Nr.  ")).isEqualTo("saskaita nr.");
        assertThat(Text.parseAmount("1,234.56")).isEqualByComparingTo("1234.56");
        assertThat(Text.parseAmount("-12,5")).isEqualByComparingTo("-12.50");
        assertThat(Text.parseDate("31.10.2026")).hasToString("2026-10-31");
        assertThatThrownBy(() -> Text.parseDate("31/10/26")).hasMessageContaining("31/10/26");
    }
}
