# Užduotis: Atsidėk – Taupymo tikslų skaičiuoklė su AI

> Specifikacija Claude Code agentams. Pirmiausia perskaitykite visą failą ir esamą repo struktūrą, tada sukurkite `docs/specs/taupymo-tikslai/PLAN.md` ir **sustokite, kol vartotojas patvirtins planą**.

## 0. Kontekstas

- Produktas: **Atsidėk** – IV mokesčių planavimo SaaS Lietuvai. Esamas stack'as: Spring Boot skaičiuoklė, paprastas HTML UI, atskira Python dalis, kuri naudoja Claude API AI asistentui.
- Aplinkos: **staging.atsidek.eu** – visi nauji endpoint'ai ir funkcijos pirmiausia keliauja čia. **Į produkciją nediegti.**
- Esamas pavyzdys, kurio principų laikytis: JSON API `/api/v1/compare`, kurį AI asistentas kviečia kaip įrankį.
- Vartotojo sąsaja ir visi tekstai – **lietuvių kalba**. Kodas, kintamieji ir commit'ai – anglų kalba.
- Savininkas mokosi Python (stiprus Java fonas). Python kodas turi būti aiškus, idiomatiškas ir su trumpais komentarais ten, kur naudojamos ne Java logikos konstrukcijos. Prie AI dalies PR'ų pridėkite trumpą „kaip tai veikia“ paaiškinimą.
- Maketas: `docs/specs/taupymo-tikslai/mockups/` (eksportuoti PNG/PDF iš dizaino drobės). Jei failų nėra, vadovaukitės 6 skyriumi.

## 1. Tikslas

Vartotojas savais žodžiais arba formoje nurodo taupymo tikslą ir šeimos situaciją. Sistema atsako:

| Režimas | Įvestis | Atsakymas |
|---|---|---|
| `YEARS` | suma, metų skaičius | mėnesio įmoka |
| `DATE` | suma, tikslo metai (ir mėnuo) | mėnesio įmoka |
| `CHILD_AGE` | suma, vaiko dabartinis amžius, tikslinis amžius | mėnesio įmoka |
| `WHEN` | suma, mėnesio įmoka | data, kada suma bus pasiekta (arba „nepasiekiama per 50 m.“) |

Tikslų tipai: `CHILD`, `CAR`, `HOME_DOWN_PAYMENT`, `OTHER`.

Rezultatas visada palyginamas su laisvais pinigais (`pajamos − išlaidos`), ir pateikiamas vertinimas su pasiūlymu, ką keisti.

**Pagrindinis principas:** kiekviena reikšmė turi kilmę – `USER` › `AI` › `STATISTICS` (toks prioritetas). UI rodo ženkliuką prie kiekvienos reikšmės, o statistinėms – šaltinį ir duomenų datą.

### MVP apimtis
- 4 režimai, 4 tikslų tipai, vienas tikslas vienu metu.
- AI ištraukimas iš laisvo teksto + vienas patikslinantis klausimas, kai trūksta kritinio lauko.
- Statistiniai numatymai iš DB.
- AI paaiškinimas.
- Eval rinkinys AI ištraukimui.

### Ne MVP (nedaryti)
Keli tikslai vienu metu, prisijungimas ir išsaugojimas, priminimai, mokėjimai, sujungimas su IV mokesčių planu.

## 2. Architektūra

```
HTML UI ──► Spring Boot (calculate, stats/defaults) ──► PostgreSQL (statistika)
   │
   └──► Python AI servisas (parse, explain) ──► Claude API
              └── kviečia Spring Boot endpoint'us kaip įrankius
```

- **Skaičiavimai – tik Spring Boot.** LLM niekada neskaičiuoja ir negeneruoja sumų.
- Python servisas: ištraukia parametrus (tool use su griežta JSON schema) ir rašo paaiškinimą. Skaičius į tekstą įterpia šablonas iš skaičiavimo JSON, ne modelis.
- Prieš pradėdami, patikrinkite, kaip repo jau organizuoti Spring Boot ir Python moduliai, ir prisitaikykite prie esamų konvencijų (paketai, testų struktūra, konfigūracija, Claude modelio pasirinkimas).

## 3. Skaičiavimo variklis (Spring Boot)

Žymėjimai: `S` – suma šiandienos kainomis, `n` – mėnesių skaičius, `r` – metinė grąža (%), `i` – metinė infliacija (%).

- `rm = r / 100 / 12`
- Tikslas su infliacija po `k` mėnesių: `FV(k) = S × (1 + i/100)^(k/12)`
- Sukaupta po `k` mėnesių su įmoka `P`: `B(P,k) = P × ((1+rm)^k − 1) / rm`; kai `rm = 0` – `P × k`
- Įmoka (režimai YEARS / DATE / CHILD_AGE): `P = FV(n) × rm / ((1+rm)^n − 1)`; kai `rm = 0` – `FV(n) / n`
- `n` pagal režimą:
  - `YEARS`: `years × 12`
  - `DATE`: mėnesiai nuo dabar iki tikslo datos, minimum 12
  - `CHILD_AGE`: `(targetAge − currentAge) × 12`, minimum 12; `targetAge > currentAge`
- `WHEN`: ieškoti mažiausio `k ∈ [1, 600]`, kai `B(P,k) ≥ FV(k)`. Jei tokio nėra – `reachable = false`.
- `paid = P × n`, `gain = max(0, B(P,n) − paid)`
- `free = income − expenses`, `share = P / free`
- Vertinimas:
  - `free ≤ 0` → `NO_FREE_CASH`
  - `share ≤ 0.20` → `COMFORTABLE`
  - `share ≤ 0.50` → `TIGHT`
  - kitaip → `UNREALISTIC`
- `series[]`: kiekvienų metų pabaigos taškai `{year, balance, target}` grafikui (iki 24 taškų, jei terminas ilgesnis – praretinti).
- Pinigams naudoti `BigDecimal`, apvalinimas `HALF_UP` iki euro centų; atsakyme sumos – eurais.
- **Testai:** vienetiniai testai kiekvienam režimui, kraštutiniams atvejams (`r = 0`, `i = 0`, `n = 12`, nepasiekiamas `WHEN`, `free ≤ 0`) ir bent 3 rankiniu būdu patikrintiems pavyzdžiams.

## 4. API kontraktai

Visi endpoint'ai: `/api/v1/goals/...`, JSON, validacija su aiškiais klaidų pranešimais lietuviškai.

### `POST /api/v1/goals/calculate` (Spring Boot)
```json
{
  "goalType": "CHILD",
  "mode": "CHILD_AGE",
  "amount": 20000,
  "years": null, "targetYear": null, "targetMonth": null,
  "childCurrentAge": 4, "childTargetAge": 18,
  "monthlyContribution": null,
  "household": { "members": 4, "workers": 2, "income": null, "expenses": 2000, "region": null },
  "assumptions": { "annualReturn": null, "annualInflation": null },
  "origins": { "amount": "AI", "expenses": "AI" }
}
```
`null` laukus serveris užpildo iš `stats/defaults` ir atsakyme pažymi kilmę `STATISTICS`.

Atsakymas:
```json
{
  "monthly": 0, "reachable": true, "reachDate": null, "months": 168,
  "futureTarget": 0, "paid": 0, "gain": 0, "freeCash": 0, "share": 0,
  "verdict": "COMFORTABLE",
  "series": [{ "year": 2027, "balance": 0, "target": 0 }],
  "resolvedInputs": {
    "income": { "value": 0, "origin": "STATISTICS", "source": "VDA ...", "asOf": "2026-..." }
  }
}
```

### `GET /api/v1/goals/stats/defaults?members=4&workers=2&region=&goalType=` (Spring Boot)
Grąžina `income`, `expenses`, `typicalAmount`, `annualInflation`, `annualReturn`. Kiekviena reikšmė pateikiama su `source`, `sourceUrl`, `asOf`.

### `POST /api/v1/goals/parse` (Python)
Įvestis: `{ "text": "..." }`. Išvestis: tie patys laukai kaip `calculate` užklausoje + `confidence` kiekvienam laukui + `missingCritical[]` + `clarifyingQuestion` (vienas klausimas su 2–4 atsakymo variantais arba `null`).

### `POST /api/v1/goals/explain` (Python)
Įvestis: `calculate` atsakymas. Išvestis: `{ "text": "...", "suggestions": ["..."] }`.

## 5. Statistikos duomenys

- PostgreSQL lentelė (pvz. `stat_defaults`): `key`, `segment` (pvz. namų ūkio dydis / dirbančiųjų skaičius / regionas), `value`, `unit`, `source`, `source_url`, `as_of`, `is_illustrative`.
- **Neišgalvokite statistinių skaičių.** Raskite tikrus šaltinius (Valstybės duomenų agentūra / osp.stat.gov.lt, Lietuvos bankas). Jei automatinis duomenų paėmimas MVP metu per sudėtingas:
  - padarykite seed failą su reikšmėmis ir nuorodomis į konkrečias lenteles;
  - kiekvieną reikšmę, kurios nepavyko patikrinti, žymėkite `is_illustrative = true`;
  - visas tokias reikšmes surašykite į `PLAN.md` kaip klausimus vartotojui.
- Planinis atnaujinimo job'as – ne MVP, bet suprojektuokite lentelę taip, kad jį būtų galima pridėti vėliau.
- UI rodo „iliustracinė“ prie `is_illustrative` reikšmių.

## 6. UI (pagal maketą)

Puslapis „Taupymo tikslai“ esamame HTML UI stiliuje, viršutinėje navigacijoje šalia „IV mokesčiai“.

1. **Hero:** „Kiek reikia atsidėti?“ + paaiškinimas, kad trūkstamos reikšmės imamos iš statistikos.
2. **AI laukas:** textarea, 2 pavyzdžių mygtukai, mygtukas „Skaičiuoti su AI“. Po paspaudimo rodomi lustai „AI suprato: …“. Jei grąžintas `clarifyingQuestion` – rodomas klausimas su mygtukais.
3. **Kairė kolona (forma):**
   1. Tikslas – 4 pasirinkimo kortelės + sumos slankiklis (1 000–100 000 €, žingsnis 500).
   2. Terminas – 4 režimų segmentinis jungiklis + režimo laukai (sąlyginiai).
   3. Namų ūkis – gyvena / dirba stepperiai (dirba ≤ gyvena), pajamos ir išlaidos slankikliai.
   4. Prielaidos – grąža (0–8 %) ir infliacija (0–6 %).
4. **Dešinė kolona (rezultatas):**
   - tamsi kortelė su pagrindiniu skaičiumi: mėnesio įmoka arba pasiekimo data;
   - 4 mini kortelės: tikslas su infliacija, iš viso įnešite, uždirbs grąža, laisvi pinigai;
   - vertinimo juosta su procentu ir patarimu;
   - kaupimo stulpelinis grafikas su tikslo linija;
   - AI paaiškinimas;
   - „Prielaidos ir šaltiniai“ sąrašas su ženkliukais;
   - disclaimer'is, kad tai ne finansinė konsultacija.
5. Rezultatas perskaičiuojamas iškart keičiant laukus (debounce ~300 ms). Ranka pakeista reikšmė įgauna kilmę `USER`.
6. Turi veikti mobiliame (kolonos išsirikiuoja viena po kita).

**Dizaino žymės:**

| Paskirtis | Spalva |
|---|---|
| Fonas | `#F4F6F3` |
| Kortelės | `#FFFFFF`, rėmelis `#DDE3DE`, radius 16px |
| Tekstas | `#13221C`, antrinis `#4A5852` |
| Pagrindinis akcentas | `#1F6B4F` |
| Ženkliukas `USER` | `#EEF0EE` / `#33413B` |
| Ženkliukas `STATISTICS` | `#E6EEF8` / `#1D4E89` |
| Ženkliukas `AI` | `#FBEEDD` / `#8A4B0B` |
| Vertinimo spalvos | žalia `#1F6B4F`, oranžinė `#8A4B0B`, raudona `#A1261B` |

Šriftai: Space Grotesk (antraštės), Inter (tekstas). Kontrastas – ne mažiau kaip 4.5:1, interaktyvių elementų dydis ≥ 44px, tikri `<label>` prie laukų.

## 7. AI dalis (Python)

- **Ištraukimas:** Claude tool use su viena funkcija `extract_goal`, kurios `input_schema` griežtai atitinka `calculate` užklausą + `confidence`. Normalizavimas atliekamas kode, ne prompte: „20 tūkst.“ → 20000, „pilnametis“ → 18, „iki vasaros“ → data.
- **Patikslinimas:** klausimas užduodamas tik jei trūksta `amount` arba termino, arba `confidence < 0.6`. Daugiausia vienas klausimas.
- **Paaiškinimas:** modelis gauna skaičiavimo JSON ir grąžina tekstą su vietos žymėmis (pvz. `{monthly}`); skaičius įterpia kodas. Testas turi tikrinti, kad tekste nėra skaičių, kurių nėra atsakymo JSON.
- **Eval'ai:** `evals/goals_parse/` – ne mažiau kaip 60 lietuviškų formuluočių su laukiamu JSON:
  - be diakritikų;
  - su klaidomis;
  - šnekamąja kalba;
  - keli vaikai;
  - „kada turėsiu…“ tipo klausimai;
  - neaiškūs atvejai.

  Skriptas skaičiuoja tikslumą kiekvienam laukui ir bendrą. Rezultatas įrašomas į `evals/results/` su data ir modelio pavadinimu.
- Laisvas vartotojo tekstas į log'us nerašomas (tik ištrauktas JSON).
- Klaidos atveju (API neprieinamas, netinkamas JSON) UI vis tiek leidžia naudotis forma be AI.
- Eval'us leisti TIK keičiant promptą, tool schemą, modelį ar normalizavimo 
  kodą. Kitiems pakeitimams – unit testai su mock'intu Claude atsakymu. 
  CI eval'ai paleidžiami rankiniu būdu (workflow_dispatch) arba PR su 
  žyme `run-evals`, ne automatiškai.

## 8. Darbo eiga agentams

1. **Planas:** sukurkite `PLAN.md` su fazėmis, užduotimis, priklausomybėmis, rizikomis ir atvirais klausimais. **Sustokite ir laukite patvirtinimo.**
2. **Fazės** (kiekviena – atskira šaka ir PR; testai turi praeiti prieš PR):
   - F1 – skaičiavimo variklis + `/calculate` + testai
   - F2 – statistikos lentelė, seed, `/stats/defaults`
   - F3 – UI su forma (be AI), prijungtas prie F1–F2
   - F4 – Python `/parse` + eval rinkinys
   - F5 – `/explain` + AI lauko integracija UI
   - F6 – deploy į staging, techninė dokumentacija (`docs/`) ir trumpas vartotojo gidas lietuviškai
3. Lygiagrečiai galima dirbti: F1 ∥ F2 ∥ F4. F3 priklauso nuo F1–F2, F5 – nuo F3–F4.
4. Kiekvienas PR turi: aprašymą, ką ir kodėl padarė, kaip testuota, bei code review kito agento ar subagento.
5. Jokių paslapčių (API raktų) repo. Naudoti esamą konfigūracijos ir aplinkos kintamųjų mechanizmą.

## 9. Definition of Done

- [ ] Visi 4 režimai veikia staging aplinkoje, rezultatai sutampa su rankiniais pavyzdžiais.
- [ ] Kiekviena reikšmė UI turi kilmės ženkliuką; statistinės – šaltinį ir datą.
- [ ] AI ištraukimo eval'ų tikslumas ≥ 90 % pagrindiniams laukams (`goalType`, `mode`, `amount`, terminas).
- [ ] Paaiškinime nėra skaičių, kurių nėra skaičiavimo atsakyme (testas).
- [ ] Forma veikia ir be AI.
- [ ] Mobilus vaizdas tvarkingas.
- [ ] Dokumentacija ir vartotojo gidas parašyti.

## 10. Ko agentai nesprendžia patys (klausti vartotojo)

- Bet kokie deploy'ai į produkciją.
- Tikrų statistinių reikšmių patvirtinimas, kai šaltinis neaiškus.
- Numatytųjų grąžos ir infliacijos prielaidų reikšmės.
- Navigacijos ar esamų puslapių struktūros keitimai už šio modulio ribų.
- Naujos priklausomybės (bibliotekos), kurios nėra standartinės esamame stack'e.
