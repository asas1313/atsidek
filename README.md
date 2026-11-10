# Atsidėk

IV mokesčių planavimo įrankis (žr. [SPEC.md](SPEC.md)). Dabartinė būsena: **spec etapai 1–2** – skaičiavimo variklis ir nemokama skaičiuoklė su el. pašto forma.

## Paleidimas

Reikia JDK 25 ir Maven.

```bash
mvn spring-boot:run   # http://localhost:8080
mvn test
```


## Struktūra

- `eu.atsidek.engine` – gryna Java, be Spring. `TaxRules.RULES_2026` – versijuojami parametrai; kasmet pridedama nauja versija.
- `eu.atsidek.web` – Thymeleaf + htmx: `/skaiciuokle` (skaičiuoklė su laukais), `/calculate` (fragmentas).
- `assistant/` – AI asistentas: atskiras Python (FastAPI) servisas, žr. žemiau. Java pusėje liko tik `/api/v1/compare`, kurį jis kviečia.

## AI asistentas (pagrindinis puslapis `/` – Java, rezultatas – atskiras servisas)

```
Vartotojas → AI asistentas (assistant/, Python) → /api/v1/compare (Atsidėk, Java)
```

Asistentas yra atskiras servisas (`assistant/`, FastAPI + Anthropic SDK). Puslapį `/` (meniu, forma, SEO) rodo Java (`asistentas.html`), o šis servisas aptarnauja tik `POST /asistentas` ir grąžina HTML fragmentą, kurį htmx įdeda į `#result`; o mokesčių skaičiavimui per HTTP kviečia Java `POST /api/v1/compare` su API raktu (`ATSIDEK_API_KEY`; Java pusėje – jo SHA-256 `API_KEY_HASHES`). Java lieka nepakeista deterministine skaičiuokle. Maršrutas: Caddyfile siunčia tik `/asistentas` į `assistant:8000`, kita – į `app:8080`. Cloudflare tunelyje / Nginx Proxy Manager reikia analogiškos kelio taisyklės (žr. DEPLOY.md).

Lokaliai: `cd assistant && python -m venv .venv && .venv/Scripts/pip install -r requirements-dev.txt && python -m pytest`.

Vartotojas savais žodžiais aprašo situaciją (pvz. „Gavau 2400 € už projektą, išlaidų turėjau apie 300“) ir be registracijos gauna rezultatą tokio paties stiliaus kortelėmis kaip skaičiuoklėje: „Ką supratau“ (ištraukti duomenys ir ko trūksta), „Rezultatas“ (GPM, VSD, PSD, kiek atsidėti per mėnesį, 30 % ir faktinių išlaidų palyginimas). Senoji skaičiuoklė su laukais perkelta į `/skaiciuokle`.

**Pagrindinė taisyklė: modelis niekada neskaičiuoja mokesčių.** Claude (`assistant/app/extractor.py`, priverstinis `tool_use` su griežta schema) tik supranta tekstą ir užpildo laukus; skaičiuoja deterministinis `TaxEngine` per `/api/v1/compare` – tas pats, kaip skaičiuoklėje. Dėl to rezultatas patikrinamas ir atkartojamas, o vartotojas mato, ką modelis suprato.

Ištraukiami parametrai: pajamos, išlaidos, išlaidų būdas (30 % / faktinės), laikotarpis, o jei aiškiai nurodyta tekste – **kitos pajamos** (darbo užmokestis, nuoma), **II pakopos pensijų kaupimas** ir **mėnesiai, kai pats moki PSD**. Nenurodyti parametrai imami pagal nutylėjimą (kitos pajamos 0, PSD 12 mėn., be II pakopos); tai parašyta rezultate. Sumos laikomos metinėmis.

**Apsauga ir kaina:** užklausų ribojimas atmintyje – `AI_RATE_LIMIT_PER_HOUR` (pagal nutylėjimą 10 per valandą vienam IP; IP iš `CF-Connecting-IP`/`X-Forwarded-For`) ir bendras `AI_DAILY_CAP` (300 per parą, kainos apsauga; perkrovus asistento servisą skaitiklis nunulinamas). Tekstas ilgesnis nei 1000 simbolių atmetamas, tekstas neišsaugomas, sistemos promptas nurodo nevykdyti vartotojo žinutėje esančių nurodymų (prompt injection). Raktas – `ANTHROPIC_API_KEY` `.env` faile (žr. `.env.example`); be jo asistentas rodo klaidą, o skaičiuoklė veikia. Modelis keičiamas `AI_MODEL`. Privatumo politika papildyta (tekstas siunčiamas Anthropic).

Prototipas (`ai/istraukimas.py`) liko kaip mokymosi/eval aplinka; produkcinis kodas yra `assistant/`.

## Prieš viešą paleidimą

Variklis sutikrintas su oficialia VMI/Sodros individualios veiklos skaičiuokle (scenarijai #1–7, #10; žr. VERIFIKAVIMO-REZULTATAI.md). Kitų pajamų (darbas) sumavimas apskaičiuotas pagal VMI DUK, oficiali skaičiuoklė jo nepalaiko. Nerealizuota: pirmųjų metų lengvata, pensininkai, PSD draudimas kitur.
Banko išrašų importas (PAPILDYNYS-01, vieno vartotojo MVP, išjungtas pagal nutylėjimą; žr. DEPLOY.md): camt.053 (.02/.08) ir Revolut CSV, klasifikavimas, peržiūra, mėnesio suvestinė, PostgreSQL (Flyway).
JSON API (`/api/**`, pvz. AI asistentui) saugomas API raktu, ne Google: antraštė `X-API-Key` arba `Authorization: Bearer`. Serveryje tik raktų SHA-256 maišos (`API_KEY_HASHES`); tuščia = API uždarytas (401). Raktą sugeneruoja `python ai/naujas_raktas.py`.

Prisijungimas: Google OAuth (GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET, AUTH_OWNER_EMAIL, AUTH_ALLOWED_EMAILS; žr. .env.example). Banko ekranai tik prisijungus.
Nepadaryta: magic link, mokėjimai ir plano tikrinimas (402), Swedbank/SEB/Paysera CSV (reikia realių pavyzdžių), priminimai, PostgreSQL.
