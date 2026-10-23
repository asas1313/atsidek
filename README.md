# Atsidėk

IV mokesčių planavimo įrankis (žr. [SPEC.md](SPEC.md)). Dabartinė būsena: **spec etapai 1–2** – skaičiavimo variklis ir nemokama skaičiuoklė su el. pašto forma.

## Paleidimas

Reikia JDK 21 ir Maven.

```bash
mvn spring-boot:run   # http://localhost:8080
mvn test
```

El. pašto registracijos kaupiamos `data/signups.csv` (kelią keičia `atsidek.signups-file`).

## Struktūra

- `lt.atsidek.engine` – gryna Java, be Spring. `TaxRules.RULES_2026` – versijuojami parametrai; kasmet pridedama nauja versija.
- `lt.atsidek.web` – Thymeleaf + htmx: `/` (skaičiuoklė), `/calculate` (fragmentas), `/signup`.

## Prieš viešą paleidimą

Variklio testų skaičiai suskaičiuoti ranka pagal SPEC.md, **nesutikrinti su VMI ir Sodros skaičiuoklėmis**. Neišspręsti punktai pažymėti `TIKRINTI` komentarais `TaxRules`/`TaxEngine`:
mokesčio kredito formulė 20–42,5 tūkst. € ruože, ar VSD/PSD atimamos iš GPM bazės, kaip IV dalis virš 42 500 € sumuojama su darbo pajamomis.
Nepadaryta (mokama dalis, kiti etapai): paskyros, magic link, mėnesio suvestinė, mokėjimai, priminimai, PostgreSQL.
