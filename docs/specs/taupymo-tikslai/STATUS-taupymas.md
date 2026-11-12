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

## B1 dalis — pabaigta (2026-11-12)

Šaka `feat/taupymas-agentas-b1`, patvirtinta per 2 review raundus. Kas pristatyta:

- `TaxResult.netMonthlyIncome = (revenue - total) / 12` — naujas laukas `/api/v1/compare`
  atsakyme (savininko sprendimas #5).
- `ContextTokenService` (`eu.atsidek.auth`) — JDK-only HMAC-SHA256 pasirašytas kontekstinis
  token'as pagal ADR-0003, 5 min. TTL, fail-closed, jei paslaptis tuščia. Naujas konfigūracijos
  kintamasis `atsidek.chat.context-token-secret` / `ATSIDEK_CHAT_TOKEN_SECRET` (būtinas, kad
  `/chat/send` veiktų; dokumentuota `DEPLOY.md`).
- `/api/v1/compare` ir `/api/v1/savings/plan` priima nebūtiną antraštę
  `X-Atsidek-Context-Token`: nesant jos — senas elgesys nesikeičia; esant galiojančiai — tapatybė
  priimama, bet `tier` visada iš naujo nustatomas serveryje per `EntitlementService` (niekada ne
  iš token'o); esant negaliojančiai — atmetama.
- Naujas Java endpoint'as `POST /chat/send` (`eu.atsidek.chat`): sukuria token'ą, kviečia Python
  `assistant` servisą (`atsidek.assistant.base-url`, pagal nutylėjimą `http://assistant:8000`,
  10 s connect/read timeout per `atsidek.assistant.timeout-ms`), persiunčia Python atsakymą.
  Reikalaujamas CSRF, leidžiama anonimams ir prisijungusiems, žinutė ribojama 1000 simbolių.
- Python: naujas įrankis `calculate_iv_taxes` (`assistant/app/tax_tool.py`), registruotas B0
  įrankių registre, naudoja esamą `AtsidekApi.compare()`, perduoda kontekstinį token'ą
  nepakeistą (tik relay, nepatikrina). Naujas `POST /internal/chat` (`assistant/app/main.py`)
  nukreipia į įrankių registrą; esamas `/asistentas` nepakeistas.
- Testai: Java 312 / 0 nepavyko (su Docker/Testcontainers, tikra Postgres), Python 92 / 0
  nepavyko. `make lint`/`make security` šiame repo tebėra tuščios komandos (nekonfiguruota).

**Architektūrinis faktas B2+ fazėms:** Java dabar yra priekinis AI chat'o sluoksnis pagal
ADR-0003 (mint'ina ir verifikuoja kontekstinį token'ą); Python yra grynas token'o relay, be
jokios verifikavimo galimybės — tyčia, per sprendimą #1.

## B2 dalis — pabaigta (PR #43, open)

Šaka `feat/taupymas-agentas-b2` (nuo main), patvirtinta per 2 review raundus. Kas pristatyta:

- Naujas įrankis `calculate_savings_plan` (`assistant/app/savings_tool.py`), apgaubiantis
  `/api/v1/savings/plan` (A3) tokiu pat tapatybės/token'o relay'u kaip B1's `calculate_iv_taxes`
  — jam nereikėjo JOKIO naujo Java kodo.
- `resolve_income` — vienintelė vieta, nustatanti `net_monthly_income`: iš ankstesnio
  `calculate_iv_taxes` rezultato (`tax_calc_id`), iš vartotojo tiesiai nurodytos sumos, arba
  `MissingIncomeError`, kai nei vieno nėra (pajamos niekada nespėliojamos iš statistikos).
- Naujas `assistant/app/errors.py`: `ToolError` hierarchija, kurią B3's agentų ciklas naudoja
  `is_error` tool_result blokams; `CalculatorError`/`UnknownToolError` į ją įtraukti (review
  round 1 radimas, B3 pasiruošimui).
- Owner sprendimas (2026-11-12): B2/B3/B6 nebėra "pairing" fazės — visos aštuonios B dalies
  fazės vykdomos savarankiškai (žr. plano Owner decision 13).
- Testai: Java 313 / Python 125, 0 nepavyko.

## B3 dalis — pabaigta (branch `feat/taupymas-agentas-b3`, PR pending)

Šaka `feat/taupymas-agentas-b3` (nuo b2), patvirtinta per 2 review raundus. Kas pristatyta:

- Pirmas šio kodo pagrindo tikras daugiažingsnis, modelio renkamasis įrankių kvietimų ciklas
  (`assistant/app/agent_loop.py::run_agent_loop`) — iki šiol visi AI kvietimai buvo priverstinai
  vieno įrankio (`tool_choice`). Ribotas žingsnių skaičius (`MAX_ITERATIONS=5`, AC-B3-1), modelis
  patsgali baigti ciklą (tekstinis atsakymas be `tool_use`).
- `ToolError` → `is_error` tool_result blokas (AC-B3-2), leidžiantis modeliui atsitaisyti arba
  paklausti vartotojo, ne nulaužti pokalbį. Modeliui rodomas tekstas (`tool_message`) gali
  skirtis nuo žmogui rodomo (`str(e)`) — review round 1 radimas dėl `CalculatorError`.
- Nepriklausomi vieno žingsnio įrankių kvietimai vykdomi paraleliai per `ThreadPoolExecutor`
  (AC-B3-3), priklausomi (turintys `tax_calc_id`) — tik po jų; `calculator.py`'s sinchroninis
  `httpx.Client` nekeistas.
- `AgentLoopResult.tool_calls` — B4's integracijos taškas (jau dekoduoti kvietimų rezultatai,
  be poreikio iš naujo skaityti `messages`/JSON).
- Rankomis rašytos `TOOL_DEF` schemos `tax_tool.py`/`savings_tool.py` (Pydantic
  `model_json_schema()` netiko — generuoja `$defs`/`$ref` įdėtiems modeliams).
- Testai: Python 164 / 0 nepavyko, 1 skip (gyvas Anthropic API testas, praleidžiamas be
  `ANTHROPIC_API_KEY`). Java nekeista šioje fazėje.

## B4 dalis — pabaigta (branch `feat/taupymas-agentas-b4`, PR pending)

Šaka `feat/taupymas-agentas-b4` (nuo b3), patvirtinta per 3 review raundus (round 1: CHANGES
REQUESTED, 3× SHOULD + 1× NIT; round 2: CHANGES REQUESTED, 1× SHOULD + 1× NIT, visi pataisyti
žemiau aprašytais pakeitimais; round 3: APPROVED). Kas pristatyta:

- `assistant/app/response_builder.py` — deterministinis atsakymo surinkimas iš
  `AgentLoopResult.tool_calls` (AC-B4-1): tvarka mokesčiai → laisvi pinigai → tikslas → prielaidos
  su kilme → teisinis atsiribojimas → vienas kvietimas prisijungti. Visi skaičiai imami TIK iš
  paskutinio sėkmingo įrankio rezultato, niekada iš modelio teksto. CTA žodynas
  (`LOCKED_FEATURE_CTA`) ir kilmės etiketės (`ORIGIN_LABELS`) — pažodžiui perkelti iš
  `taupymo-tikslai.html` (review round 1 radimas: brūkšnio simbolis nesutapo dviejuose
  sakiniuose — pataisyta, testas sutvirtintas iki tikslaus baitų sutapimo).
- `assistant/app/system_prompt.py` — tikras produkcinis sisteminis prompt'as (AC-B4-3): draudimas
  duoti investavimo patarimus, draudimas spėlioti pajamas, modelio vaidmuo ribojamas tik
  įvadu/paaiškinimu/patikslinančiais klausimais (niekada ne skaičiais). Review round 1 radimas:
  viena frazė turėjo neegzistuojančią lietuvišką žodžio formą — pataisyta.
- `assistant/app/agent_client.py` (naujas) — prompt caching (AC-B4-4): `cache_control` sisteminiam
  prompt'ui ir paskutiniam įrankio apibrėžimui (niekada nekeičiant bendrų `TOOL_DEF` konstantų
  vietoje), kešavimo metrikos logavimas per kvietimą. `agent_loop.py` pakeistas kviesti šį modulį
  tiesioginio `client.messages.create()` vietoje — vienintelis pakeitimas tame faile.
- Review round 1 radimas: `general_tip` (Java, formalus „jūs“ registras) pirmą kartą realiai
  rodomas vartotojui šiame atsakyme, tiesiai prieš neformalaus „tu“ registro CTA sakinį — tono
  neatitikimas. Pataisyta `GENERAL_TIP_INFORMAL_LT` žodynu, verčiančiu 4 fiksuotas Java eilutes į
  neformalų registrą (kryžminiu testu su Java šaltiniu, kad neištrūktų iš sinchronizacijos).
- Sąmoningai NEBŪVO sujungta su `main.py`/`/internal/chat` — tikras agento ciklo įjungimas į
  gyvą HTTP endpoint'ą paliktas vėlesnei fazei (B6's eval'ų poreikiui/B7's staging integracijai).
- Testai: Python 215 / 0 nepavyko, 1 skip. Java nekeista šioje fazėje.

## B5 dalis — pabaigta (branch `feat/taupymas-agentas-b5`, PR pending)

Šaka `feat/taupymas-agentas-b5` (nuo b4), patvirtinta per 2 review raundus (round 1: CHANGES
REQUESTED, 1× BLOCKER + 1× SHOULD + 2× NIT, visi pataisyti žemiau aprašytais pakeitimais;
round 2: APPROVED, 2× nebloškiantis NIT, viena pataisyta papildomai dėl nuoseklumo). Kas
pristatyta:

- `assistant/app/chat_limiter.py` (naujas) — `ChatLimiter.try_acquire(key, tier)` (AC-B5-1):
  anoniminis srautas (be `tier`) dalijasi VIENU bendru dienos skaitliuku (ta pati filosofija kaip
  `goals_limiter.GoalsAiLimiter`); prisijungęs vartotojas (su `tier`) turi SAVO, PER-`key`,
  dienos skaitliuką, ribojamą `tier_daily_caps.get(tier, <griežčiausia sukonfigūruota riba>)` —
  neatpažintas `tier` niekada nereiškia neribotos prieigos. Skaitliukų skaičius ribojamas kaip
  `limiter.RateLimiter`'s `MAX_TRACKED_IPS` (seno rakto įrašai valomi, kai viršijama
  `MAX_TRACKED_KEYS`). `tier`/`key` yra visiškai nepermatomos, kviečiančiojo pateiktos reikšmės —
  šis modulis NEI dekoduoja, NEI tikrina ADR-0003 context token'o; tikras `tier` nustatymas
  paliktas vėlesnei `main.py` sujungimo fazei (kaip B2's `MissingIncomeError` ir B4's
  nesujungimas). "Viena žinutė = vienas vienetas nepriklausomai nuo įrankių kvietimų skaičiaus"
  yra struktūrinis dalykas (kviečiantysis turi kviesti `try_acquire` lygiai vieną kartą prieš
  agento ciklą), ne ką šis modulis pats seka.
- `assistant/app/usage_logging.py` (naujas) — `log_usage(...)` (AC-B5-2): per pokalbio žinutę
  (ne per API kvietimą — kitas granuliarumas nei B4's `agent_client.py` kešo metrikos) surenka
  struktūrinį naudojimo/kainos įrašą. Kaina skaičiuojama VISADA `Decimal` (CLAUDE.md pinigų
  taisyklė), naudojant tikrus `claude-haiku-5-5` tarifus (pasitikrinti per `claude-api` skill'ą
  2026-11-12, ne iš atminties), apvalinama per `ROUND_HALF_UP` iki sveikų centų (`int`). Viena
  `json.dumps(...)` eilutė į stdout (owner sprendimas #9: tik stdout JSON eilutės, be naujo
  agregavimo — B7 vidurkius skaičiuos skriptu).
- Review round 1 radimas (BLOKUOJANTIS): nepriklausomo testuotojo pridėti 7 testai buvo likę
  neįkelti (`git status` modified, neįtraukti į commit'ą) — švarus "checkout" neturėjo šios
  aprėpties. Pataisyta: testai įkelti į commit'ą `b057395`.
  Review round 1 radimas (SHOULD): neigiami token'ų skaičiai tyliai generuodavo neigiamą
  `cost_cents` vietoje klaidos (pažeidžia CLAUDE.md "fail loudly" taisyklę; sugadintų B7's
  vidurkio skaičiavimą). Pataisyta: `_cost_cents` dabar meta `ValueError` kiekvienam iš 4
  token'ų laukų, jei reikšmė neigiama.
  Review round 2: APPROVED, be BLOCKER/SHOULD radimų; 2× NIT (tipo anotacijų nuoseklumas,
  `tool_call_count`/`duration_ms` neturi analogiško neigiamumo patikrinimo — žemesnio
  prioriteto, paliktas ateičiai, nes neveikia kainos skaičiavimo). Pirmas NIT pataisytas papildomai
  (`Optional[str]` → `str | None`).
- Sąmoningai NEBŪVO sujungta su `main.py` — šis limiter'is ir log'inimas kol kas nenaudojami
  gyvame kelyje; sujungimas paliktas vėlesnei fazei (sutampa su B4's precedentu).
- Testai: Python 237 / 0 nepavyko, 1 skip. Java nekeista šioje fazėje (patvirtinta per
  `git diff feat/taupymas-agentas-b4..feat/taupymas-agentas-b5 --stat` — tik 2 nauji app failai
  + 2 nauji testų failai).
