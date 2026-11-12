# Būklės ataskaita: taupymo modulis vs. `PLAN-taupymas-agentas.md` (A0)

Palyginimo pagrindas: `docs/specs/taupymo-tikslai/spec.md` (originali, jau įgyvendinta specifikacija,
F1–F6), `docs/specs/taupymo-tikslai/PLAN-taupymas-agentas.md` (šis planas, A1–A6/B0–B7), ir realus kodas
(`src/main/java/eu/atsidek/goals/**`, `src/main/java/eu/atsidek/entitlement/**`,
`src/main/java/eu/atsidek/web/TaxApiController.java`, `src/main/resources/db/migration/*.sql`).

**Svarbu:** `PLAN-taupymas-agentas.md` 4 eilutėje nurodo susijusį dokumentą `docs/PLAN-taupymas.md`
(ir SKILL savo antraštėje mini `docs/PLAN-taupymas-agentas.md`) — **šis failas repozitorijoje
neegzistuoja** (patikrinta failų paieška visame repo, taip pat `.git` istorijoje nerasta). Negalima
spėti jo turinio; jei jis egzistuoja kažkur kitur (pvz. tik vietiniame failų sistemos aplanke pas
savininką), reikia jį atnešti arba pašalinti nuorodą.

## A dalis. Funkcija → būklė → kur kode → kas trūksta

| Funkcija (plano skyrius) | Būklė | Kur kode | Kas trūksta |
|---|---|---|---|
| A1. `GoalsEngine` (grynos funkcijos, `YEARS`/`DATE`/`CHILD_AGE`/`WHEN`, infliacijos korekcija, grąžos prielaida) | **Padaryta** (PR #33, sulieta į `main`) — sprendimas #1 (pavadinimo nekeisti) ir #2 (numatytoji grąža 0%) | `eu.atsidek.goals.GoalsEngine` + `GoalMode`, `StatDefaultsService`, testai `GoalsEngineTest`/`SavingsGoalServiceTest`/`StatDefaultsServiceTest` | `GoalsEngine.DEFAULT_ANNUAL_RETURN` ir `StatDefaultsService.DEFAULT_ANNUAL_RETURN` pakeisti į 0%; UI (`taupymo-tikslai.html`) sliderio numatytoji reikšmė ir šaltinių sąrašas atnaujinti. |
| A2. Statistikos duomenys (`is_illustrative` žymė, šaltinis + data) | **Padaryta** — sprendimas #5 (versijavimo nedaryti, `is_illustrative` palikti) patikrintas šakoje `feat/taupymas-agentas-a2` | `stat_defaults` lentelė (`V3__stat_defaults.sql`, `V4__seed_stat_defaults.sql`), `StatDefaultsService`/`StatDefaultsStore` | Audituotos visos 16 esamų eilučių: kiekviena turi šaltinį ir yra `is_illustrative=true` (nė viena neturi `as_of` datos, nė viena neišgalvota). Pridėtas `hasTraceableOrigin()` invariantas + testas, saugantis nuo būsimos eilutės be šaltinio/žymės. |
| A3. `POST /api/v1/savings/plan` (tier/core/locked_features/premium kontraktas) | **Padaryta (peržiūrėta)** — plonas sluoksnis virš `GoalsEngine`, sprendimų #1/#4/#6 pagrindu, šakoje `feat/taupymas-agentas-a3` | Naujas paketas `eu.atsidek.goals.plan`: `SavingsPlanRequest`/`Response`/`Core`/`Premium`/`Tier`, `SavingsPlanService`, `SavingsPlanApiController` | `tier`/`core`/`premium`/`locked_features` kontraktas įgyvendintas; `locked_features` naudoja `SAVINGS_IV_LINK` (ne `SAVINGS_IRREGULAR_INCOME`, sprendimas #4). Peržiūros metu rasta ir pataisyta: vidinių lauko pavadinimų nutekėjimas klaidų pranešimuose (13 atvejų), Content-Type/trailing-JSON griežtumo spragos. Savininko sprendimui palikta: `household.adults→workers` segmentavimo prielaida (šiuo metu nekenksminga, bet trapi); `premium` lauko buvimas = prisijungęs, ne = turi teises (kol beta vėliava įjungta, abu sutampa). |
| A4. Teisės (`tier` iš Google OAuth sesijos, beta vėliava) | **Padaryta** — patvirtinta skirtu A4 patikros žingsniu, kodo pakeitimų nereikėjo | `SavingsPlanService`/`SavingsPlanApiController` (A3), `EntitlementService`/`Feature`, beta vėliava `app_setting.goals_beta_mode_enabled` | Patikrinta: `tier` imamas tik iš sesijos (nėra tier/role lauko užklausoje — negalima suklastoti); beta ON suteikia visas 3 užrakintas funkcijas, beta OFF + nėra teisių įrašų → `FREE` (testuota abi kryptys); anonimo/prisijungusio `premium` lauko testai teisingi; nėra Python→Java vidinio autentifikavimo kodo (tyčia, B1). |
| A5. UI (nemokama forma pagal maketą, kvietimas prisijungti iš `locked_features`) | **Padaryta** — sprendimas #1: `/goals/calculate` nepakeistas, CTA generuojamas iš to paties `SavingsPlanService` šakoje `feat/taupymas-agentas-a5` | `taupymo-tikslai.html` (dinaminis CTA), naujas `GET /goals/savings/locked-features` + `LockedFeaturesController`, `SavingsPlanService.lockedFeatures()` | Statinis CTA pakeistas dinaminiu, generuojamu iš `locked_features`; viena (SAVINGS_IV_LINK prioritetu) žinutė rodoma anonimui, CTA paslepiamas, kai `locked_features` tuščias. Tono nesuderinamumas (formalu/neformalu) rastas ir pataisytas peržiūros metu. |
| A6. Staging + DoD (visi testai žali, rankinis patikrinimas, `STATUS-taupymas.md` atnaujintas) | **Paruošta patikrai, bet nepatikrinta** | `docs/specs/taupymo-tikslai/A6-staging-checklist.md` | Negalima pasiekti staging'o iš šios aplinkos (žr. žemiau) ir negalima paleisti pilno `make test` (nėra Docker/Testcontainers šiame sandbox'e). Parašytas `curl` patikrų sąrašas (4 režimai, klaidų atvejai, `locked_features`, anonimas/prisijungęs) savininkui/CI paleisti po PR #33–#37 sulietų. A dalies DoD **neįvykdytas**, kol šios patikros nepaleistos ir nepavyksta. |

## Du A0 reikalaujami gyvi patikrinimai

- **`POST /api/v1/savings/plan` veikimas staging'e ir A3 kontrakto atitikimas** — **negalima patikrinti**:
  šioje sandbox aplinkoje nėra leidžiama išeinanti tinklo prieiga (`curl` į `staging.atsidek.eu` buvo
  atmestas sandbox leidimų, ne tinklo klaidos). Be to, kodo lygmeniu endpoint'as **neegzistuoja** (žr. A3
  eilutę aukščiau), tad net su tinklo prieiga atsakymas būtų `404`. Reikia rankinio patikrinimo iš
  aplinkos, kuri turi tinklo prieigą prie staging.
- **`/api/v1/compare` atsakyme: grynosios mėnesio pajamos + mėnesio mokesčių atidėjimas** — **patikrinta
  tiesiai kode** (be tinklo): `TaxApiController.compare()` grąžina `TaxComparison` → `TaxResult`
  (`eu.atsidek.engine.TaxResult`). Jame **yra** `monthlyReserve` laukas — tai ir yra mėnesio mokesčių
  atidėjimo suma, reikalinga B1. **Grynųjų mėnesio pajamų lauko nėra** tiesiogiai — jį reikėtų skaičiuoti
  iš esamų laukų (`revenue`, `total`, `expenses`), nes atsakyme jų skirtumas šiuo metu negrąžinamas kaip
  atskiras pavadintas laukas. Taigi B1 užduočiai nurodytas "nedidelis Java pakeitimas" bus reikalingas
  bent jau grynųjų mėnesio pajamų laukui (bet B dalis šiuo metu nevykdoma — tik faktas užfiksuotas).

## Išvada

A dalis **nėra** paruošta be papildomo darbo — A3 (pats API) neegzistuoja, A1 turi principinį prielaidų
numatytojo konfliktą su jau veikiančiu `GoalsEngine`, A2 turi dalį reikiamos infrastruktūros, bet ne
tiksliai tą workflow'ą, kurio reikalauja šis planas. Prieš tęsiant A1–A6, savininkui reikia nuspręsti:

1. Ar A3 kuriamas kaip **naujas**, atskiras endpoint'as/DTO rinkinys (`/api/v1/savings/plan`), ar
   išplečiamas/pervadinamas esamas `/goals/calculate` + `SavingsGoalController` rinkinys jo vietoje.
2. Kaip sutaikyti numatytąją grąžos prielaidą (esama `3%` vs. šio plano reikalaujama `0%`) — naujam
   varikliui ar bendrai.
3. Ar `docs/PLAN-taupymas.md` (nerastas) reikalingas prieš tęsiant, ir jei taip, iš kur jį gauti.

Pagal plano ir šio paleidimo nurodymą, darbas **sustojo čia** — A1–A6 nepradėti, laukta savininko
„patvirtinu" arba atsakymų į aukščiau išvardintus klausimus.

## Savininko sprendimai (2026-11-11)

Savininkas atsakė į visus 3 klausimus ir patvirtino A1–A5 vykdymą. Pilnas sprendimų sąrašas įrašytas
`PLAN-taupymas-agentas.md` skyriuje „A0 sprendimai (savininkas, 2026-11-11)“. Santrauka:

1. Naujo variklio nekurti — `GoalsEngine` lieka; `/api/v1/savings/plan` = plonas API sluoksnis virš jo;
   tier/`core`/`premium`/`locked_features` logika iškeliama į `SavingsPlanService`, naudojamą ir API, ir UI.
2. Numatytoji grąža 0 % visur — keisti `GoalsEngine.DEFAULT_ANNUAL_RETURN`, patikrinti poveikį DB įrašams.
3. `docs/PLAN-taupymas.md` nereikalingas, nuoroda pašalinta.
4. `SAVINGS_IRREGULAR_INCOME` nekurti, naudoti esamą `SAVINGS_IV_LINK`.
5. A2: `is_illustrative` paliekama, versijavimo nereikia.
6. A4: `tier` tik pagal sesiją; Python→Java vidinis autentifikavimas perkeltas į B1.
7. B1: patikrinti, ar `revenue`/`total` metinės, prieš pridedant `net_monthly_income`.

Toliau vykdoma A1–A5, kiekviena fazė atskiroje šakoje su testais ir PR (žr. `PLAN-taupymas-agentas.log.md`).

## A dalis — pabaigta (2026-11-11)

A0–A6 visos sumerge'intos į `main` (PR #32–#38), plius šalutinis radinys `/api/v1/compare`
nežinomų JSON laukų priėmimo pataisymas (PR #39). A dalis veikia produkcijoje.

**A1 DB-impact sprendimas (owner, 2026-11-12):** 3%→0% numatytosios grąžos keitimas priimamas
be migracijos. Esami įrašai, kuriuose `assumptions.annualReturn IS NULL`, toliau skaitomi kaip
0% (naudojant naująją numatytąją reikšmę), atgalinio užpildymo (backfill) nebus. Tai galutinis
sprendimas, ne laukiantis.

## B dalis — pradėta (2026-11-12)

Planas: `docs/specs/taupymo-tikslai/PLAN-taupymas-agentas.B.plan.md`. Visi atviri klausimai
atsakyti savininko 2026-11-12; žr. plano skyrių „Owner decisions (2026-11-12)“ ir
`docs/adr/0003-ai-agent-python-java-service-identity.md` (Accepted). B0 vykdoma pirma; B1 Java
dalis (kontekstinis token'as, chat endpoint'as) pradedama tik po B0 patvirtinimo.
