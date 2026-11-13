# Planas: taupymo modulio užbaigimas → AI agentas (mokesčiai + taupymas)

> Planas Claude Code agentams. Vieta repozitorijoje: `docs/specs/taupymo-tikslai/PLAN-taupymas-agentas.md`.
> Susijęs dokumentas: `docs/specs/taupymo-tikslai/spec.md`.
> **A0 atlikta ir savininko patvirtinta 2026-11-11** (žr. `STATUS-taupymas.md` ir žemiau esančius sprendimus). Toliau vykdoma A1–A5.

## Eiliškumas

1. **A dalis – taupymo modulis** užbaigiamas iki galo ir veikia staging'e be AI agento.
2. **B dalis – AI agentas** prijungiamas tik tada, kai A dalies Definition of Done įvykdytas.

B dalis remiasi A dalies API kontraktu, todėl jo nekeisti be savininko pritarimo.

## Patvirtinti principai (galioja abiem dalims)

- **Skaičiai nekeliauja per LLM.** Mokesčių įrankis grąžina `tax_calc_id`, taupymo įrankis priima jį, o grynąsias pajamas ir mokesčių sumą įstato Python handleris iš užklausos konteksto.
- **Nemokama / premium skirtis įgyvendinama kode, ne prompte.** API anonimui premium sekcijų tiesiog negrąžina (laukų nėra, ne `null`). Beta laikotarpiu premium gauna visi prisijungę.
- **Jokių investavimo patarimų.** Nevardijami produktai, instrumentai, fondai, bankai ar taupymo būdai. Grąža – tik vartotojo įvesta prielaida, numatytoji 0 %.
- **Pajamos niekada neimamos iš statistikos.** Jei jų nėra, agentas paklausia. Išlaidos gali būti imamos iš statistikos ir pažymimos `STATISTICS`.
- **Laisvi pinigai = grynosios pajamos − mokesčių atidėjimas − išlaidos.** Mokesčiai rodomi atskira eilute.
- **Viena vartotojo žinutė = vienas AI limito vienetas**, nepriklausomai nuo įrankių kvietimų skaičiaus.
- **Kiekviena reikšmė turi kilmę:** `USER` › `AI` › `STATISTICS`.
- Prie kiekvieno rezultato: „Skaičiavimai informaciniai, tai nėra finansinis ar investavimo patarimas.“
- Viskas tik į **staging.atsidek.eu**. Į produkciją nediegti.
- Kiekviena fazė – atskira šaka, testai, PR. Python kodas su trumpais komentarais ten, kur konstrukcijos skiriasi nuo Java; prie AI PR'ų – skyrelis „Kaip tai veikia“.

---

## A dalis. Taupymo modulio užbaigimas

### A0. Būklės patikra (sustoti po šios fazės)

- Palyginkite repozitoriją su `spec.md` ir `PLAN-taupymas.md`.
- Sukurkite `docs/STATUS-taupymas.md` su lentele: funkcija → būklė (padaryta / dalinai / nepradėta) → kur kode → kas trūksta.
- Patikrinkite, ar `/api/v1/savings/plan` veikia staging'e ir ar jo kontraktas atitinka A3.
- Patikrinkite, ar `/api/v1/compare` atsakyme yra grynosios mėnesio pajamos ir mėnesio mokesčių atidėjimo suma (reikės B1).
- Toliau A1–A6 vykdykite tik tai, ko trūksta pagal ataskaitą.

### A0 sprendimai (savininkas, 2026-11-11)

1. Naujo variklio nekurti — `GoalsEngine` lieka su tuo pačiu pavadinimu. `/api/v1/savings/plan` kuriamas kaip plonas API sluoksnis virš `GoalsEngine`, pagal A3 kontraktą (snake_case). Tier logika (`core`/`premium`/`locked_features`) iškeliama į vieną servisą (`SavingsPlanService`), kurį naudoja ir naujas API, ir esamas UI. Esami `/goals/*` endpoint'ai nekeičiami; UI kvietimas prisijungti generuojamas iš to paties serviso `locked_features`.
2. Numatytoji grąža **0 % visur** — pakeisti `GoalsEngine.DEFAULT_ANNUAL_RETURN`, atnaujinti testus. Patikrinti, ar `StatDefaultsService.DEFAULT_ANNUAL_RETURN` turi sutapti. Patikrinti, ar išsaugoti tikslai DB remiasi numatytąja grąža (null `annualReturn`), ir pranešti, kiek įrašų paveikta.
3. `docs/PLAN-taupymas.md` nereikalingas — nuoroda pašalinta iš šio plano.
4. `SAVINGS_IRREGULAR_INCOME` nekurti — naudoti esamą `SAVINGS_IV_LINK` kaip `locked_features` reikšmę.
5. A2: `is_illustrative` paliekama, versijavimas nedaromas. Kiekviena reikšmė turi turėti šaltinį ir datą; iliustracinės reikšmės API atsakyme pažymimos.
6. A4: `tier` nustatomas tik pagal sesiją (Google OAuth). Python→Java vidinio autentifikavimo mechanizmas perkeliamas į B1.
7. B1 pastaba (ateičiai): prieš pridedant `net_monthly_income` į `TaxResult`, patikrinti, ar `revenue`/`total` yra metinės sumos — mėnesio reikšmė turi būti skaičiuojama Java pusėje.

### A1. Skaičiavimo variklis (Java)

- Naudoti esamą `GoalsEngine` (nekurti naujo `SavingsEngine` — sprendimas #1). Visi 4 režimai (`YEARS`, `DATE`, `CHILD_AGE`, `WHEN`) ir infliacijos korekcija jau atitinka šio plano reikalavimus pagal A0 ataskaitą — tik patikrinti, nekeisti be reikalo.
- Pakeisti numatytąją grąžos prielaidą į **0 %** (sprendimas #2): `GoalsEngine.DEFAULT_ANNUAL_RETURN`. Patikrinti `StatDefaultsService.DEFAULT_ANNUAL_RETURN` panaudojimą ir suderinti.
- Patikrinti poveikį esamiems išsaugotiems tikslams DB, kai `assumptions.annualReturn` yra `null` (t. y. naudojamas numatytasis) — pranešti paveiktų įrašų skaičių.
- Unit testai: esami testai atnaujinti numatytajai 0 % grąžai; nauji testai, kur jų trūksta pagal A0 ataskaitą.

### A2. Statistikos duomenys

- Naudoti esamą `stat_defaults` lentelę ir `is_illustrative` žymę (sprendimas #5) — **versijavimo nedaryti**, naujos lentelės nekurti.
- Patikrinti, kad kiekviena reikšmė turi šaltinį (`source`) ir datą (`as_of`/`source_date`); trūkstamas papildyti arba žymėti `is_illustrative = true`.
- `/api/v1/savings/plan` atsakyme iliustracinės reikšmės turi būti pažymėtos (pvz. per `origin: STATISTICS` įrašo `source`/papildomą lauką, nurodantį, kad skaičius iliustracinis).

### A3. API `POST /api/v1/savings/plan`

Validacija tokia pati kaip `/compare`: griežtas JSON, nežinomi laukai → 400 su priežastimi, `BigDecimal` ribos.

Įvestis (kiekvienas laukas pasirenkamas, išskyrus tikslą):

```json
{
  "goal": { "type": "HOME_DOWN_PAYMENT", "amount": 15000, "mode": "YEARS", "years": 5 },
  "net_monthly_income": 3100,
  "monthly_tax_setaside": 900,
  "monthly_expenses": null,
  "household": { "adults": 2, "children": 2 },
  "assumptions": { "annual_return_pct": 0, "annual_inflation_pct": null }
}
```

Išvestis:

```json
{
  "tier": "FREE",
  "core": {
    "monthly_contribution": 0,
    "free_cash_monthly": 0,
    "share_of_free_cash_pct": 0,
    "fits": true,
    "general_tip": "…",
    "assumptions": [ { "field": "monthly_expenses", "value": 0, "origin": "STATISTICS", "source": "…", "source_date": "…" } ],
    "disclaimer": "…"
  },
  "locked_features": ["SAVINGS_MULTI_GOAL", "SAVINGS_IV_LINK", "SAVINGS_SCENARIOS"],
  "premium": { }
}
```

- **Laisvų pinigų formulė** (B6 incident fix, 2026-11-13, žr. B6-log.log): `free_cash_monthly =
  net_monthly_income - monthly_expenses`. `monthly_tax_setaside` į šią formulę NIEKADA
  neįeina - jis TIK rodymui (vartotojas jį jau matė mokesčių skaičiavimo žingsnyje). Priežastis:
  `net_monthly_income` (B1, `TaxResult.netMonthlyIncome = (revenue - total) / 12`) JAU yra
  grynosios pajamos PO mokesčių; `monthly_tax_setaside` (`TaxResult.monthlyReserve = total / 12`)
  yra TA PATI mokesčių suma - atimant abu, mokesčiai būtų atimami du kartus.
- `premium` sekcija grąžinama tik turint teises; anonimui jos nėra.
- `locked_features` – sąrašas, iš kurio agentas ir UI formuoja kvietimą prisijungti. Naudoti tik esamas `Feature` enum reikšmes (`SAVINGS_IV_LINK`, ne naują `SAVINGS_IRREGULAR_INCOME` — sprendimas #4).
- `general_tip` – deterministinis šablonas pagal `share_of_free_cash_pct` ribas, ne AI tekstas.
- Tier/`core`/`premium`/`locked_features` logika gyvena viename servise `SavingsPlanService` (sprendimas #1), kurį naudoja ir šis endpoint'as, ir esamas UI (A5).

### A4. Teisės (entitlements)

- `tier` nustatomas Java pusėje tik pagal prisijungusio vartotojo Google OAuth sesiją (sprendimas #6) — vidinis Python→Java autentifikavimas šiai fazei nereikalingas, perkeliamas į B1.
- Beta vėliava: visi prisijungę gauna premium.
- Testai: anonimo atsakyme nėra `premium` lauko; prisijungusio – yra.

### A5. UI

- Forma pagal maketą: nemokama versija su vienu tikslu, rezultatu, prielaidomis su kilme ir disclaimer'iu.
- Kvietimas prisijungti generuojamas iš `locked_features` (pvz., „Jūsų pajamos svyruoja – prisijungę pamatysite, kiek atsidėti stipresniais ir silpnesniais mėnesiais“). Jokių blur'intų ar netikrų skaičių.

### A6. Staging ir A dalies Definition of Done

- Įdiegta į staging.atsidek.eu, visi testai žali.
- Rankinis patikrinimas: 4 režimai, anonimas ir prisijungęs, statistikos reikšmės pažymėtos kilme.
- `STATUS-taupymas.md` atnaujintas – visos eilutės „padaryta“.

---

## B dalis. AI agentas: mokesčiai + taupymas

### B0. Esamo asistento pertvarkymas (be elgsenos pokyčių)

- Įrankių registras: `dict` iš įrankio pavadinimo į handlerį.
- Pydantic modeliai įrankių įvestims (Java DTO + validacijos analogas).
- `RequestContext` objektas vienai vartotojo žinutei: vartotojo id, tier, tarpiniai įrankių rezultatai.
- Esami testai ir eval'ai turi praeiti nepakitę.

### B1. Mokesčių įrankis `calculate_iv_taxes`

- Kviečia `/api/v1/compare`. Jei A0 parodė, kad atsakyme trūksta grynųjų mėnesio pajamų ar mėnesio mokesčių atidėjimo, pirmiausia nedidelis Java pakeitimas.
- Prieš pridedant `net_monthly_income` į `TaxResult` (sprendimas #7): patikrinti, ar `revenue`/`total` yra metinės ar mėnesio sumos. Mėnesio reikšmė turi būti skaičiuojama Java pusėje, ne spėjama.
- Rezultatas išsaugomas `RequestContext`, modeliui grąžinamas su `tax_calc_id`.
- Vidinis Python→Java autentifikavimas (vartotojo id antraštėje, serviso token'as — žr. A4 sprendimą #6) įgyvendinamas šioje fazėje.

### B2. Taupymo įrankis `calculate_savings_plan`

- Įvestis: tikslo laukai, išlaidos, namų ūkis, prielaidos, pasirenkamas `tax_calc_id`. **Nėra** `net_monthly_income` lauko, kai yra `tax_calc_id`.
- Handleris pagal `tax_calc_id` įstato `net_monthly_income` ir `monthly_tax_setaside` iš konteksto. Nežinomas id → įrankio klaida.
- Užklausa tik apie taupymą: modelis gali perduoti vartotojo nurodytas grynąsias pajamas (`origin: USER`). Jei pajamų nėra – įrankio klaida „reikia pajamų“, agentas paklausia vartotojo.
- API kviečiamas su vartotojo id, todėl modelis gauna tik tai, kas priklauso jo tier'ui.

### B3. Agento ciklas

- Iteracijų limitas (pvz., 5), aiškus klaidos pranešimas jį viršijus.
- Įrankių klaidos grąžinamos kaip `is_error` tool_result, kad modelis galėtų pataisyti arba paklausti.
- Nepriklausomus kvietimus leisti lygiagrečiai (pvz., du mokesčių variantai), priklausomus – nuosekliai.

### B4. System prompt ir prompt caching

- Atsakymo struktūra: mokesčiai → laisvi pinigai → tikslas → prielaidos su kilme → disclaimer → vienas kvietimas prisijungti.
- Kvietimą formuluoti tik iš `locked_features`, vieną, konkretų.
- Investavimo patarimų draudimas, pajamų neprielaidavimas.
- Prompt caching system prompt'ui ir įrankių aprašams. Užregistruoti cache hit rodiklius.

### B5. Limitai ir stebėsena

- Viena vartotojo žinutė = vienas limito vienetas.
- Kiekvienai užklausai logas: tokenai (įvesties, cache, išvesties), įrankių kvietimų skaičius, trukmė, kaina.

### B6. Eval'ai

Rinkinys apie 30 atvejų, trys grupės: tik mokesčiai, tik taupymas, kombinuotos. Kiekviename – anoniminio ir prisijungusio vartotojo variantai.

Tikrinama:

- teisingi įrankiai ir teisinga jų tvarka;
- skaičiai atsakyme sutampa su įrankių rezultatais (išrenkami iš teksto ir lyginami);
- nėra investavimo patarimų (taisyklių patikra + LLM vertintojas su rubrika);
- anonimui nėra premium duomenų;
- trūkstant pajamų agentas klausia, o ne spėja.

Eval'ai paleidžiami kiekviename B dalies PR.

### B7. Staging ir B dalies Definition of Done

- Įdiegta į staging.atsidek.eu, eval'ų rezultatai PR'e.
- Rankinis patikrinimas su keliomis realiomis kombinuotomis užklausomis.
- Užfiksuota vidutinė kombinuotos užklausos kaina ir trukmė.

---

## Mokymosi pastaba

B2, B3 ir B6 yra vertingiausia AI engineering dalis. Šias fazes savininkas daro kartu su agentu: agentas siūlo, paaiškina, savininkas rašo arba peržiūri kodą eilutė po eilutės. Kitas fazes agentas gali vykdyti savarankiškai.

Pastaba: šis sprendimas pakeistas — žr. PLAN-taupymas-agentas.B.plan.md "Owner decisions" #13 (2026-11-12): B2/B3/B6 vykdomi savarankiškai.

## Ko agentai patys nesprendžia

- Diegimas į produkciją.
- Statistinių reikšmių patvirtinimas, kai šaltinis neaiškus.
- A3 API kontrakto keitimai po A dalies užbaigimo.
- Naujos priklausomybės, kurių nėra esamame stack'e.
- Teisių matricos (kas nemokama, kas premium) keitimai.
