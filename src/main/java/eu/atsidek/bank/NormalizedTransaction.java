package eu.atsidek.bank;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Visi adapteriai grąžina šią struktūrą; likusi sistema niekada nemato originalaus formato. */
public record NormalizedTransaction(
        String accountIban,       // vartotojo sąskaita (CSV be IBAN: null)
        LocalDate bookingDate,
        BigDecimal amount,        // visada teigiama; kryptis atskirai
        String currency,
        Direction direction,
        String counterpartyName,
        String counterpartyIban,
        String remittance,
        String structuredRef,
        String bankRef,
        String endToEndId,
        String sourceFormat) {}
