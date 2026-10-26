# Atsidėk

IV mokesčių planavimo įrankis (žr. [SPEC.md](SPEC.md)). Dabartinė būsena: **spec etapai 1–2** – skaičiavimo variklis ir nemokama skaičiuoklė su el. pašto forma.

## Paleidimas

Reikia JDK 21 ir Maven.

```bash
mvn spring-boot:run   # http://localhost:8080
mvn test
```


## Struktūra

- `lt.atsidek.engine` – gryna Java, be Spring. `TaxRules.RULES_2026` – versijuojami parametrai; kasmet pridedama nauja versija.
- `lt.atsidek.web` – Thymeleaf + htmx: `/` (skaičiuoklė), `/calculate` (fragmentas).

## Prieš viešą paleidimą

Variklis sutikrintas su oficialia VMI/Sodros individualios veiklos skaičiuokle (scenarijai #1–7, #10; žr. VERIFIKAVIMO-REZULTATAI.md). Kitų pajamų (darbas) sumavimas apskaičiuotas pagal VMI DUK, oficiali skaičiuoklė jo nepalaiko. Nerealizuota: pirmųjų metų lengvata, pensininkai, PSD draudimas kitur.
Nepadaryta (mokama dalis, kiti etapai): paskyros, magic link, mėnesio suvestinė, mokėjimai, priminimai, PostgreSQL.
