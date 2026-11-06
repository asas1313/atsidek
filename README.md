# Atsidėk

IV mokesčių planavimo įrankis (žr. [SPEC.md](SPEC.md)). Dabartinė būsena: **spec etapai 1–2** – skaičiavimo variklis ir nemokama skaičiuoklė su el. pašto forma.

## Paleidimas

Reikia JDK 21 ir Maven.

```bash
mvn spring-boot:run   # http://localhost:8080
mvn test
```


## Struktūra

- `eu.atsidek.engine` – gryna Java, be Spring. `TaxRules.RULES_2026` – versijuojami parametrai; kasmet pridedama nauja versija.
- `eu.atsidek.web` – Thymeleaf + htmx: `/` (skaičiuoklė), `/calculate` (fragmentas).

## Prieš viešą paleidimą

Variklis sutikrintas su oficialia VMI/Sodros individualios veiklos skaičiuokle (scenarijai #1–7, #10; žr. VERIFIKAVIMO-REZULTATAI.md). Kitų pajamų (darbas) sumavimas apskaičiuotas pagal VMI DUK, oficiali skaičiuoklė jo nepalaiko. Nerealizuota: pirmųjų metų lengvata, pensininkai, PSD draudimas kitur.
Banko išrašų importas (PAPILDYNYS-01, vieno vartotojo MVP, išjungtas pagal nutylėjimą; žr. DEPLOY.md): camt.053 (.02/.08) ir Revolut CSV, klasifikavimas, peržiūra, mėnesio suvestinė, H2 failas.
JSON API (`/api/**`, pvz. AI asistentui) saugomas API raktu, ne Google: antraštė `X-API-Key` arba `Authorization: Bearer`. Serveryje tik raktų SHA-256 maišos (`API_KEY_HASHES`); tuščia = API uždarytas (401). Raktą sugeneruoja `python ai/naujas_raktas.py`.

Prisijungimas: Google OAuth (GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET, AUTH_OWNER_EMAIL, AUTH_ALLOWED_EMAILS; žr. .env.example). Banko ekranai tik prisijungus.
Nepadaryta: magic link, mokėjimai ir plano tikrinimas (402), Swedbank/SEB/Paysera CSV (reikia realių pavyzdžių), priminimai, PostgreSQL.
