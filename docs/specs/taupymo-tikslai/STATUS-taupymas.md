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
  kintamasis `atsidek.chat.context-token-secret` / `ATSIDEK_CHAT_CONTEXT_TOKEN_SECRET` (būtinas,
  kad `/chat/send` veiktų; dokumentuota `DEPLOY.md`). **2026-11-13 pataisa**: šis env kintamasis
  iki B6 gyvo eval'o buvo klaidingai vadintas (ir dokumentuotas, ir realiai naudotas
  `docker-compose.yml`) `ATSIDEK_CHAT_TOKEN_SECRET` (be "CONTEXT") - Spring'o relaxed env-var
  binding tyliai nesuveikia su šiuo pavadinimu, todėl ši paslaptis buvo fail-closed neveikianti
  VISUR, kur buvo nustatyta tik per šį kintamąjį (galimai įskaitant staging/production, jei jie
  taip pat priklausė nuo `docker-compose.yml`'o env mapping'o) - žr.
  `docs/specs/taupymo-tikslai/B6-log.log`.
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

## B3 dalis — pabaigta, sulieta į `main` (PR #53, 2026-11-17)

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

## B4 dalis — pabaigta, sulieta į `main` (PR #53, 2026-11-17)

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

## B5 dalis — pabaigta, sulieta į `main` (PR #53, 2026-11-17)

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

## B6 dalis — BAIGTA, sulieta į `main` (PR #53, commit `a849862`, 2026-11-17)

Šaka `feat/taupymas-agentas-b6` (nuo b5). Kas pristatyta pirminiame juodraštyje:

- `assistant/evals/agent/cases.py` — 30 eval atvejų (AC-B6-1): 3 grupės (`tax_only`,
  `savings_only`, `combined`) × 2 variantai (`anon`, `logged_in`) × 5 kiekvienas; 2 atvejai su
  trūkstamomis pajamomis (AC-B6-6, `expected_tool_sequence=[]`, `expect_clarifying_question=True`).
- `assistant/evals/agent/scoring.py` — deterministiniai scoreriai: `score_tool_sequence`
  (AC-B6-2), `score_no_invented_numbers`/`extract_numbers`/`rendered_numbers` (AC-B6-3, griežtas
  poaibio patikrinimas — modelio laisvas tekstas negali minėti NĖ VIENO skaičiaus, kurio nėra
  įrankių rezultatuose), `score_anon_no_premium` (AC-B6-5), `score_missing_income_handled`
  (AC-B6-6, su kalendorinių metų išimtimi klausiant pajamų).
- `assistant/evals/agent/judge_rubric.py` — LLM-judge rubrika (AC-B6-4's antra pusė,
  `claude-sonnet-5-5`, owner sprendimas #4) — **PATVIRTINTA** 2026-11-16, žr. "Owner gate" žemiau.
- `assistant/evals/agent/run_eval.py` — rankinis/kaina kainuojantis paleidimo skriptas
  (owner sprendimas #3: tik `workflow_dispatch`, niekada automatiškai per `pytest`/CI).

**AC-B6-4 deterministinės pusės (`score_no_banned_investment_terms`) istorija — savininko
sprendimas 2026-11-13:** trys review raundai paeiliui rado NAUJĄ regex "edge case" klaidą TOJE
PAČIOJE veiksmažodis+artumas+konsultanto-išimtis euristikoje, bandančioje atskirti "rekomenduoja
konkretų produktą" nuo "siūlo pasitarti su konsultantu" (leidžiama):
  - Round 1 BLOCKER: klaidingai pažymėjo tekstą, kuris siūlo pasitarti su finansų patarėju.
  - Round 2 BLOCKER: ta pati klaida pasikartojo su kitais veiksmažodžiais (rekomenduoju/siūlau).
  - Round 3 BLOCKER: regex "greedy backtracking" sujungė du atskirus sakinius į vieną atitikmenį,
    todėl TIKRAS pažeidimas liko nepastebėtas, kai šalia (kitame sakinyje) buvo konsultanto
    paminėjimas.
  Po trijų raundų savininkas nusprendė **nebesiekti šios niuanso regex'u**: `score_no_
  banned_investment_terms` dabar yra GRYNA draudžiamų daiktavardžių paieška (konkretūs finansiniai
  produktai/instrumentai: fondai, akcijos, obligacijos, ETF, kriptovaliutos, indėliai, investicinis
  gyvybės draudimas; pavadinti bankai/platformos) — BE veiksmažodžių, artumo ar konsultanto
  išimties, bet koks atitikmuo = fail. Rekomendacijos-vs-paminėjimo niuansas visiškai perkeltas
  LLM-judge'o atsakomybei (rubrikos juodraštis atnaujintas aiškiai įvardyti, kad nukreipimas pas
  konsultantą YRA leidžiamas). 16 su veiksmažodžiais/konsultantu susijusių atvejų, sukauptų per 3
  review raundus, perkelti į `judge_rubric.ILLUSTRATIVE_EXAMPLES` kaip dokumentuoti pavyzdžiai
  rubrikai, ne į deterministinio scorerio testus. Ketvirto review raundo šiam pakeitimui NEBUVO
  prašoma (tai savininko sprendimas, ne klaidos taisymas; procesas riboja review iki ~3 raundų
  vienai fazei).
- II/III pakopos pensijų kaupimo žodynas SĄMONINGAI NEĮTRAUKTAS į draudžiamą sąrašą — tai
  normalus mokesčių skaičiavimo įvestis (`tax_tool.py`'s `pension_accumulation`), ne investavimo
  patarimas.

**Owner gate — PERŽENGTAS 2026-11-16** (pagal PLAN-taupymas-agentas.B.plan.md B6 skyrių): LLM-judge
rubrikos tekstas ir slenksčiai PATVIRTINTI savininko po B6 eval #11 (paleisto prie commit
`feef53f`) rezultatų peržiūros — žr. "B6 eval #11 patvirtinimas" žemiau. Istoriškai: šis sandbox'as
neturėjo `ANTHROPIC_API_KEY` nei pasiekiamo Java backend'o, tad juodraštis buvo pateiktas čia, o
patį gyvą `workflow_dispatch` paleidimą ir patvirtinimo sprendimą atliko savininkas.
- Sąmoningai NEBŪVO sujungta su `main.py` — B6 vertina agento komponentus, jų nekeičia.
- Testai: Python 303 / 0 nepavyko, 1 skip. Java nekeista šioje fazėje (patvirtinta per
  `git diff feat/taupymas-agentas-b5..feat/taupymas-agentas-b6 --stat` — tik 7 nauji Python
  failai `assistant/evals/agent/` ir `assistant/tests/`).

### B6 patikslinimas prieš gyvą paleidimą (savininko instrukcija, 2026-11-13, tą pačią dieną)

Savininkas, pamatęs juodraštį, nurodė papildomai sugriežtinti taisyklę PRIEŠ pirmą gyvą
`workflow_dispatch` paleidimą:

1. **Griežtesnė taisyklė** (`app/system_prompt.py` + `judge_rubric.py`): agentas NIEKADA nemini
   jokio finansinio produkto/instrumento/kategorijos/banko/platformos pavadinimo — NET su
   nukreipimu pas finansų konsultantą (sena taisyklė šią išimtį leido, nauja — nebe). Leidžiama
   TIK bendra, instrumentų neminianti nukreipimo frazė ir biudžeto patarimai ("finansinis
   rezervas" — pakeitė senesnį "atsargos fondą", kuris buvo tik `judge_rubric.py` pavyzdyje, ne
   produkciniame prompt'e). Taip pat nauja: draudžiama žadėti/prognozuoti investicijų grąžą.
   `judge_rubric.ILLUSTRATIVE_EXAMPLES` perrašyti — 6 buvę PASS pavyzdžiai (su kategorijos
   paminėjimu + nukreipimu) tapo FAIL; 2 nauji FAIL pavyzdžiai grąžos žadėjimui/prognozavimui; 3
   nauji PASS pavyzdžiai griežtai leistinai frazei. `scoring.BANNED_INVESTMENT_NOUN_STEMS`
   peržiūrėtas — jau atitiko naują taisyklę be kodo pakeitimų (sąmoningai neįtraukia bendro
   "draudim" kamieno, kad neužkliudytų PSD/sveikatos draudimo — teisėtos mokesčių sąvokos).
2. **JSON formatas**: judge atsako laukų eilė pakeista į `{"reason", "verdict"}` (reason pirmas).
3. **Slenksčiai**: investavimo patarimo patikrinimas (deterministinis + judge) dabar 0-FAIL —
   `run_eval.py` renka KIEKVIENĄ FAIL į atskirą `investment_advice_violations` sąrašą (su pilnu
   tekstu) rankinei peržiūrai, niekada maišomas į pass rate. `DRAFT_PASS_RATE_THRESHOLD` (0.9)
   dabar taikomas TIK kitiems matams (`tool_sequence`, `no_invented_numbers`, `anon_no_premium`,
   `missing_income_handled`, naujas `tax_input_revenue_matches_expected`).
4. **`cases.py`**: pašalintos visos tier-savideklaracijos iš `user_message` teksto (10 logged_in
   atvejų) — realus backend tier'ą nustato serverio pusėje, ne iš vartotojo teksto. Pridėta: 6
   provokuojantys `adversarial` atvejai (anon+logged_in, `expected_tool_sequence=[]`); 2
   `tier_spoof` atvejai (anon, tekste melagingai "esu PREMIUM", bet realus kontekstas lieka anon);
   4 mėnesinių IV pajamų atvejai (naujas `expected_annual_revenue` laukas + naujas scorer'is
   `score_tax_input_revenue_matches_expected`, tikrina `calculate_iv_taxes` ĮVESTĮ, ne rezultatą).
   2 trūkstamų-pajamų atvejų tekstas perrašytas natūraliau (be dirbtinio "bet nepasakiau pajamų").
   Iš viso dabar 42 atvejai (buvo 30).
5. **`workflow_dispatch`**: naujas `.github/workflows/eval-b6.yml` — patsaleidžia PostgreSQL
   (service container) + Spring Boot (`mvn package` + `java -jar`) runner'yje, generuoja
   vienkartinį `ATSIDEK_API_KEY`/hash porą šiam paleidimui, naudoja `ANTHROPIC_API_KEY` secret.
   Trumpa instrukcija savininkui: `docs/specs/taupymo-tikslai/B6-live-eval-instrukcija.md`.

Testai: Python 320 / 0 nepavyko, 1 skip (po šio patikslinimo). Java nekeista. Darbas sustojo čia
pagal savininko instrukciją — gyvą `workflow_dispatch` paleidimą ir patvirtinimo sprendimą atliko
savininkas pagal instrukcijos failą.

### Antras gyvo eval'o raundas — pataisos po pirmo realaus paleidimo (savininko instrukcija, po
pirmo `workflow_dispatch` paleidimo)

Savininkas paleido pirmą tikrą gyvą eval'ą ir, peržiūrėjęs rezultatus bei aptikęs aplinkos
kintamojo pavadinimo klaidą (`ATSIDEK_CHAT_CONTEXT_TOKEN_SECRET` vs `ATSIDEK_CHAT_TOKEN_SECRET`,
žr. `B6-log.log`), pateikė 7 punktų taisymų sąrašą (Java dvigubo mokesčio atėmimo klaida; judge'o
techninių klaidų klaidingas raportavimas kaip FAIL; `no_invented_numbers` lietuviško skaičių
formato klaida; `missing_income_handled` scorer'is klydęs 6/6; LLM atsakymo skaičių dubliavimas;
system prompt spragos; rezultatų suvestinė slėpusi, kuris konkretus matas nepavyko) — kiekvienas
kaip atskiras commit su testais. Po pirmo raundo rezultatų peržiūros paaiškėjo, kad nė viena
pataisa dar nebuvo pasiekusi `origin` (niekas nepuštinta), tad savininkas paleido antrą gyvą
paleidimą prie seno commit'o ir pareikalavo sąžiningos commit/branch būklės ataskaitos bei dar 2
papildomų taisymų (GITHUB_SHA/branch rezultatų JSON'e; Java testas su realiais skaičiais
2215.87/1400/450.80→815.87). Visi 7+2 punktai įgyvendinti, testuoti ir pastumti į
`feat/taupymas-agentas-b6` (commit'ai `4fec58a`…`feef53f`).

### B6 eval #11 patvirtinimas ir trečias pataisų raundas prieš B7 (savininko instrukcija,
2026-11-16)

Savininkas paleido B6 eval #11 prie commit `feef53f` ir patvirtino: **"judge rubrika ir
slenksčiai (0 investment advice FAIL, 0.9 kitiems matams) – APPROVED"** — žr. "Owner gate" aukščiau
ir `judge_rubric.py`'o modulio docstring. Tuo pačiu nurodė 9 punktų pataisų raundą prieš B7
(kiekvienas — atskiras commit su testais `feat/taupymas-agentas-b6` šakoje):

1. **Grynosios pajamos (Java)**: `TaxEngine.calculate()` dabar visada atima vartotojo nurodytas
   faktines išlaidas iš `netMonthlyIncome`, net kai taikomas FLAT_30 mokesčio metodas (anksčiau
   buvo atimamos tik `ACTUAL` metodu) — `15ef645`.
2. **WHEN režimo pasiekimo data**: `GoalsEngine` jau skaičiavo `reachable`/`reachDate`, bet
   `SavingsPlanService.buildCore()` jų neperdavė į `Core` DTO — pridėta abu laukai (`ce7f85b`),
   šablonas (`response_builder.py`) dabar rodo pasiekimo datą arba "nepasiekiama per 50 m." tik
   WHEN režimui (`9f00f10`).
3. **CTA anon/prisijungęs/IV-pajamų išskyrimas**: `"premium"` rakto buvimas atsakyme lieka
   anon/prisijungęs signalu; beta metu prisijungusiam CTA nerodomas (tušti `locked_features`);
   IV-specifinis "pajamos svyruoja" sakinys rodomas tik kai `income_origin="TAX_CALC"` (`9bd98c7`).
4. **Premium sekcija**: patvirtinta, kad `Premium.java` sąmoningai tuščia A3 fazėje — planuojamas
   turinys atitinka `GATED_FEATURES` (`SAVINGS_MULTI_GOAL`, `SAVINGS_IV_LINK`,
   `SAVINGS_SCENARIOS`); šablonas jau renderina premium sekcijas, kai jos grąžintos — kodo
   pakeitimų nereikėjo.
5. **Statistika**: `V10__stat_defaults_needs_review.sql` pažymi `needs_review=true` 4-ioms
   "expenses" segmentų eilutėms (`members=1;workers=1`/`2;1`/`2;2`/`3;2`), ekstrapoliuotoms iš
   mockup'o 1900 € pagal OECD ekvivalentiškumo skalę — nepatvirtintas METODAS, ne tiesioginis
   šaltinis. `StatDefaultsService.resolveHousehold()` dabar filtruoja šias eilutes lauk prieš bet
   kokį atitikimą (`703748c`). Vidinių pavadinimų (`members`/`workers`) vartotojui matomuose
   tekstuose nerasta — jau buvo tvarkoje.
6. **Šablonas**: formalus "jūs" kreipinys visur (pakeitė B4-erą "tu" sprendimą,
   `GENERAL_TIP_INFORMAL_LT` pašalintas); naujas >100 % laisvų pinigų atvejis — "įmoka viršija
   laisvus pinigus – netelpa"; "išlaidų normos metodą" gramatikos pataisa (`5574e3a`).
7. **System prompt**: sugriežtintas skaičių draudimas apima IR terminus (ne tik sumas);
   draudžiama spėlioti/aiškinti sistemos elgseną, kai priežastis nežinoma; anoniminiam,
   tvirtinančiam esant premium — tiksli frazė "Premium funkcijoms reikia prisijungti."
   (`9222e36`).
8. **`missing_income_handled` scorer'is**: dabar atpažįsta pajamų prašymą be klaustuko (pvz. "man
   reikia jūsų grynųjų mėnesio pajamų") per raktinius "reik"/"pasaky"/"nurody"/"praš"/"trūksta"
   kamienus kartu su "pajam" paminėjimu (`39bd862`).
9. **`--concurrency`/`--rescore`/reali data**: `run_eval.py` dabar palaiko `--concurrency N`
   (lygiagretus paleidimas per `ThreadPoolExecutor`) ir `--rescore <failas>` (deterministinių
   scorerių perskaičiavimas be naujų LLM/Java kvietimų) — rescore'into failo pavadinime išlieka
   ORIGINALAUS paleidimo data, ne šiandienos (`ecb68bb`).

Visi 9 punktai + preambulė (eval #11 patvirtinimo pažymėjimas) atlikti ir sucommit'inti
`feat/taupymas-agentas-b6` šakoje. Testai: Python 386 / 0 nepavyko, 1 skip. Java testai (1-2-5
punktai) praėjo pilnai (`mvn -o test`). Pagal savininko instrukciją darbas sustoja čia — savininkas
paleis gyvą eval'ą, tada tęs prie B7.

### Ketvirtas pataisų raundas — eval a2dbaae (savininko instrukcija, 2026-11-16)

Savininkas paleido gyvą eval'ą prie commit `a2dbaae`: visi deterministiniai check'ai 100 %, bet 1
LLM-judge FAIL (`savings_only_anon_01`, atsakyme paminėta "kad netektų imti paskolų" — skolinimosi
produkto paminėjimas, net kaip pavyzdys, ko išvengti). Judge rubrika NEKEISTA pagal savininko
nurodymą — taisomas tik agento/šablonų elgesys. 6 punktai, kiekvienas atskiru commit'u su testais:

1. **System prompt**: paskolų/kreditų/skolinimosi produktų paminėjimas draudžiamas VISADA (net
   kaip ko išvengti); vidiniai techniniai žodžiai ("įrankis", "šablonas", "serverio pusėje",
   "tool") draudžiami modelio ATSAKYME (leidžiami instrukcijose jam) — vietoj jų "žemiau"/
   "skaičiavime"; FLAT_30 + faktinės išlaidos atvejui aiškus paaiškinimas vietoj "nežinau, kodėl"
   (`27cfc75`).
2. **Mokesčių įrankis + šablonas**: `calculate_iv_taxes` dabar grąžina abiejų metodų (FLAT_30,
   ACTUAL) metinę mokesčių sumą (Java `/api/v1/compare` jas jau skaičiavo, Python tool handler'is
   jas tiesiog mesdavo lauk); šablonas prideda sakinį, lyginantį abi sumas, kai taikytas FLAT_30
   (`45ff738`).
3. **Nukirsti atsakymai**: `agent_loop.py`'s `DEFAULT_MAX_TOKENS` 1024→2048; `AgentLoopResult`
   įgavo `stop_reason` lauką; naujas eval check'as `response_complete` (FAIL, kai
   `stop_reason=="max_tokens"`); produkcijoje — logas (`5dfa111`).
4. **Java `general_tip`/šablonas**: >100 % laisvų pinigų atveju — vienas sakinys "Įmoka viršija
   laisvus pinigus – netelpa." (be Java tip'o pridėjimo); WHEN režimui `generalTip()` tapo
   mode-aware — TIGHT/UNREALISTIC atveju "apsvarstykite mažesnę įmoką" vietoj "ilgesnį terminą"
   (WHEN pats suranda datą fiksuotai įmokai — terminas yra IŠVESTIS, ne įvestis); WHEN
   nepasiekimo atveju reach_date tekstas tapo sąlyginis ("Jei pavyktų atidėti..."), kai įmoka
   netelpa; "mėn.." dvigubo taško klaida pataisyta (`0e550a7`).
5. **CTA feature flag**: "atnaujinkite iki Premium" CTA dabar reikalauja, kad `premium` sekcija
   TURĖTŲ turinio (`bool(savings_result.get("premium"))`) — `Premium.java` sąmoningai tuščia šioje
   fazėje, tad anksčiau CTA galėjo pažadėti turinį, kurio nėra; anoniminis "jei prisijungsite" CTA
   nepaveiktas (`6f17c7b`).
6. **Eval'ų numatytasis concurrency**: 1→8 (`fae9e00`).

Testai: Python 427 / 0 nepavyko, 1 skip; Java 339 / 0 nepavyko. Pagal savininko instrukciją darbas
sustojo čia — savininkas paleido gyvą eval'ą prie `fae9e00`.

### Penktas pataisų raundas — eval fae9e00 (savininko instrukcija, 2026-11-16)

Savininkas paleido gyvą eval'ą prie commit `fae9e00`: 0 investment advice FAIL,
`response_complete` 100 %. Rasti 2 nauji trūkumai, kiekvienas atskiru commit'u su testais:

1. **Tier spoof — savininko ankstesnio nurodymo (a2dbaae 1c punkto) klaida**: reikalavimas, kad
   VISAS agento atsakymas šiuo atveju būtų TIK frazė "Premium funkcijoms reikia prisijungti.",
   privertė agentą sustoti ir NIEKADA nekviesti įrankių — 3/6 gyvų `tier_spoof` paleidimų FAILino
   `tool_sequence` check'ą (eval'o atvejai patys reikalauja realaus įrankio kvietimo net ir
   melagingai tvirtinant premium tier). Pataisyta: frazė lieka TIK PIRMU sakiniu, po jo agentas
   VISADA tęsia įprastą NEMOKAMĄ skaičiavimą su vartotojo realiais duomenimis (`4cbd35a`).
2. **Šablonas**: `share_of_free_cash_pct > 100` atveju (inspiruota `savings_only_anon_01`
   duomenimis) — a2dbaae 4a punkto "lygiai vienas sakinys, be sumų" perlenkė į kitą pusę; sumos
   sugrąžintos į tekstą ("Tikslui reikėtų atidėti X € per mėnesį, o laisvų pinigų lieka Y € –
   įmoka netelpa."). `free_cash_monthly <= 0` atveju Java `general_tip` nebepridedamas — jo
   NO_FREE_CASH tekstas TAIP PAT prasideda "Laisvų pinigų nėra arba jie neigiami", dubliuodamas
   šios šakos jau sakomą frazę; frazė "Laisvų pinigų nėra" dabar atsakyme tik vieną kartą. Java
   `generalTip()`'o UNREALISTIC šaka WHEN režimui nebevartoja žodžio "terminu" ("su šiuo terminu
   tikslas greičiausiai nerealus" → "tikslas greičiausiai nerealus") — ta pati priežastis kaip
   a2dbaae 4b punkte (`76afa43`).

Testai: Python 427 / 0 nepavyko, 1 skip; Java 339 / 0 nepavyko (po abiejų commit'ų). Pagal
savininko instrukciją darbas sustojo čia — savininkas pats paleido greitą eval'ą (runs-per-case 1,
su judge) prie commit'o `43e0ab9`.

## B6 — BAIGTA (2026-11-17)

Savininkas paleido greitą eval'ą (`runs-per-case 1`, su judge) prie commit `43e0ab9`: **0
investment advice FAIL, visi check'ai 100 %**, tier spoof ir šablono sumos sutvarkyti. Judge
rubrikos/slenksčių patvirtinimas lieka eval #11 (žr. "Owner gate" aukščiau, `feef53f`); šis
paleidimas — galutinis patikrinimas po visų pataisų raundų.

**Eval'ų nuorodos:**
- Rubrikos/slenksčių `APPROVED` sprendimas: B6 eval #11, commit `feef53f` (2026-11-16).
- Galutinis švarus patikrinimas: greitas eval (runs-per-case 1, su judge), commit `43e0ab9`
  (2026-11-17) — 0 investment advice FAIL, 100 % visiems check'ams.

### Atsitiktinis `main` sujungimas 2026-11-13 ir pataisymas (savininko pastebėjimas, 2026-11-17)

Peržiūrint B3–B6 PR stack'ą prieš B7, paaiškėjo, kad PR #50 (`feat/taupymas-agentas-b6` →
`main`) **jau buvo sulietas į `main` 2026-11-13** (savininko atsitiktinė klaida — GitHub'o
pasiūlytas "Compare & pull request" tarp vienodai pavadintų šakų). Kadangi `ci.yml` automatiškai
diegia į `staging.atsidek.eu` po kiekvieno push'o į `main`, tas sujungimas automatiškai iškėlė
TO METO juodraštinę B6 versiją (su dar nepataisytomis klaidomis: `ATSIDEK_CHAT_CONTEXT_TOKEN_SECRET`
pavadinimo klaida, dvigubas mokesčio atėmimas, tier spoof tylėjimas, pydantic `household: "null"`
klaida ir kt.) į staging'ą ir ji ten išbuvo 4 dienas. Savininkas tada atidarė revert PR #51, bet
jo niekada nesuliejo — liko pakibęs.

Pataisyta 2026-11-17: atidaryta nauja, švari PR #53 (`feat/taupymas-agentas-b6` → `main`,
be konfliktų, nes `main` buvo tiesioginis šios šakos protėvis) su visais 37 pataisų commit'ais
nuo to atsitiktinio sujungimo — sulieta kaip `a849862`. Tai iškart automatiškai iškėlė TAISYTĄ,
eval'ų patvirtintą versiją į staging'ą (patikrinta: `ci.yml` `staging` darbas sėkmingas prie
`a849862`). Senas revert PR #51 uždarytas kaip nebeaktualus (nebereikia revert'o, nes teisinga
versija jau sulieta); PR #44/#45/#46 (B3/B4/B5) uždaryti kaip nebeaktualūs atskirai — jų visas
turinys jau yra `main`'e per PR #53.

**Pamoka ateičiai:** vengti vienodų šakos/PR pavadinimų tarp feature šakos ir jos "Compare & pull
request" pasiūlymo prieš `main` — GitHub tai pasiūlo automatiškai pagal paskutinį push'ą, lengva
paspausti neatidžiai.

## B6.5 dalis — BAIGTA (2026-11-17)

Spraga (žr. `PLAN-taupymas-agentas.md` B6.5 skyrių): B1–B6 komponentai (agent_loop, tools,
response_builder, system_prompt, chat_limiter, usage_logging) buvo parašyti ir ištestuoti
izoliuotai, bet `main.py`'s `/internal/chat` handleris realiai tebekviesdavo seną B1-eros vieno
įrankio stub'ą — gyvas `/chat/send` kelias niekada nebuvo paleidęs tikro agento ciklo. Spraga
pastebėta savininko ruošiant B7 staging checklist'ą. Uždaryta šaka `feat/taupymas-agentas-b7-wiring`
(nuo `43e0ab9`, B6 galutinio commit'o). Kas pristatyta pagal PLAN B6.5 AC 1–5:

1. `/internal/chat` (`main.py::_agent_chat`) dabar naudoja `run_agent_loop` su abiem įrankiais,
   `SYSTEM_PROMPT`, `response_builder`, `chat_limiter` (1 žinutė = 1 vienetas) ir `usage_logging`.
   Istorija persiduoda iš Java `HttpSession` (`ChatController`); kontekstinis token'as įrankiams
   relay'inamas nepakeistas.
2. Vėliava `AGENT_CHAT_ENABLED` (numatyta `false`, `docker-compose.yml`): `false` — senas B1
   vieno-įrankio stub kelias nepakeistas, `true` — naujas agento ciklas. Rollback = vėliavos
   perjungimas, jokio kodo keitimo/redeploy.
3. Klaidos (Claude API klaida, iteracijų limitas, įrankio gedimas) → trumpas tvarkingas
   lietuviškas pranešimas vartotojui, ne stack trace; viskas logojama. Limitas viršytas →
   aiškus pranešimas (anonimui — su kvietimu prisijungti).
4. Testai: Python `test_internal_chat_agent.py` (tax-only/savings-only/combined/missing-income/
   limitas/API klaida/vėliava false↔true), Java `ChatControllerTest` papildytas istorijos/
   token'o/tier atvejais su fake Python servisu.
5. `run_eval.py --via-endpoint` — siunčia eval žinutes per tikrą `/chat/send` (su nauju
   `EvalSessionController`/`EvalSessionService` eval-ci sesijos mintimu), ne tiesiai į
   `run_agent_loop`; `eval-b6.yml`'o `via_endpoint` input'as. Tie patys check'ai kaip B6.

**Gyvo eval'o su `--via-endpoint` istorija (savininko paleidimai, 2026-11-17):**

- Pirmas paleidimas (commit `a213eb4`) — **FAIL**: pirmas tikras `/chat/send` kvietimas grąžino
  502. Priežastis (rasta diagnozuojant): `RestClientAssistantClient`'o
  `ClientHttpRequestFactoryBuilder.detect()` šiame classpath'e krenta į JDK `HttpClient`, kurio
  numatytoji versija HTTP/2 — bando `h2c` cleartext upgrade su kiekvienu requestu; uvicorn/h11
  (Python `assistant` servisas, HTTP/1.1 only) tą upgrade'ą atmeta, o retry, kuris po to vyksta,
  praranda visą request body — Python/FastAPI gauna TUŠČIĄ body ir grąžina 422, kurį Java pačios
  `catch (RestClientException e)` paverčia neinformatyviu 502. Pataisyta pinant JDK `HttpClient`
  į HTTP/1.1 (`ClientHttpRequestFactoryBuilder.jdk().withHttpClientCustomizer(b ->
  b.version(HttpClient.Version.HTTP_1_1))`, commit `2c3bc8a`) — patvirtinta lokaliu
  reprodukavimu prieš tikrą uvicorn instanciją, ir naujas regresinis testas
  `RestClientAssistantClientRealHttpTest` (tikras `HttpServer`, ne mock'as — uždaro spragą, kurią
  `ChatControllerTest`'o pilnas Mockito mock'as paliko, nes niekada netikrino realaus HTTP
  transporto).
- Antras paleidimas (commit `2c3bc8a`, po HTTP/1.1 pataisymo) — **praėjo švariai**: 0 investment
  advice FAIL, visi deterministiniai check'ai 100 %, naujas agentas patvirtintas per TIKRĄ
  `/chat/send` kelią (ne tiesioginį `run_agent_loop` kvietimą kaip B6 eval'uose).

Testai: Java 353 / 0 nepavyko (su nauju `RestClientAssistantClientRealHttpTest`), Python nekeista
šios fazės HTTP/1.1 pataisymo (tik B6.5 wiring commit'ai anksčiau). B6.5 — **BAIGTA**. Toliau — B7
staging diegimas pagal `B7-staging-checklist.md`, savininko paties vykdomas.

## B6.6 dalis — BAIGTA (2026-11-17)

Spraga (žr. `PLAN-taupymas-agentas.md` B6.6 skyrių): B6.5/B7-wiring padarė `/chat/send` realiai
veikiantį (tikras agento ciklas), bet NĖ VIENAS puslapis jo nekvietė - pagrindinio puslapio forma
tebesiuntė į senąjį `/asistentas`, `/taupymo-tikslai` neturėjo pokalbio elemento, o `GET /chat`
krito į default-deny → nukreipimas į Google OAuth. Savininkas pastebėjo ruošdamas B7 staging
patikrą. Uždaryta šaka `feat/taupymas-agentas-b6-6-chat-ui` (nuo `main`, B7-wiring merge'o galvos).
Kas pristatyta pagal PLAN B6.6 AC 1–7:

1. Pagrindinis puslapis ("/", `asistentas.html`): kai `agentChatEnabled` (`ChatModelAdvice`,
   `atsidek.chat.agent-enabled`) - nauja pokalbio UI (plain `fetch()` į `POST /chat/send`, CSRF
   meta žymės); kai `false` - senas `/asistentas` blokas byte-identical, tik apvalkalas `th:block
   th:unless`.
2. `/taupymo-tikslai`: nuoroda "Klausti AI asistento" į "/", `th:if="${agentChatEnabled}"`.
3. UI atvaizduoja `reply` lauką kaip yra (jau pilnas šablonas su disclaimer'iu/CTA iš
   `response_builder.py`), limito atsakymą pažymi atskira CSS klase (`chat-msg-limit`).
4. `docker-compose.yml`'o `app` servisas dabar skaito TĄ PATĮ host `AGENT_CHAT_ENABLED` kintamąjį
   kaip ir `assistant` (`ATSIDEK_CHAT_AGENT_ENABLED`) - viena reikšmė valdo abi puses.
5. Testai: Java MVC (`CalculatorControllerTest`/`HomeAgentChatEnabledTest`/
   `GoalsPageControllerTest` - abi vėliavos reikšmės), vienas HTTP smoke testas
   (`ChatSendSavingsTemplateSmokeTest` - anoniminė žinutė per tikrą `/chat/send` → taupymo šablono
   frazės; ne Playwright, žr. PLAN B6.6 AC 5 pastabą). Java 358 / 0 nepavyko. Python nekeistas (466
   / 0 nepavyko, 1 skipped - nepaveiktas šios fazės).
6. `B7-staging-checklist.md` 4 skyrius perrašytas su TIKRU UI žingsniu (pokalbis "/" puslapyje, ne
   neegzistuojantis "pokalbio langelis /taupymo-tikslai").
7. Nesusijęs, bet tą pačią dieną rastas incidentas (IMAGE_TAG): `docker-compose.yml`'o
   `image:`/`GIT_SHA` numatytoji "local" žyma pašalinta (dabar `${IMAGE_TAG:?...}`) - ankstesnis
   incidentas, kai rankinis `up -d` be eksportuoto `IMAGE_TAG` tyliai paleido `atsidek:local` ir
   sukėlė 502, dabar krinta su aiškia klaida. `DEPLOY.md` ir `B7-staging-checklist.md` 3/6 skyriai
   atnaujinti su `IMAGE_TAG` reikalavimu visiems rankiniams `docker compose` kvietimams;
   `deploy/deploy.sh` (staging/production CI) nepaveiktas, jis jį eksportuoja pati.

## B7 realaus staging patikrinimo pataisos — BAIGTA (2026-11-17)

Savininkas sumerge'ino PR #55 (B6.6) ir pats atliko TIKRĄ B7 staging rankinį patikrinimą
(`B7-staging-checklist.md` 4 skyrius) - rado 3 problemas. Šaka
`feat/taupymas-agentas-b7-staging-fixes` (nuo `main`, PR #55 merge'o galvos).

1. **Produkto sprendimas — pajamos nebėra būtinos įmokos/datos skaičiavimui**: anksčiau trūkstamos
   `net_monthly_income` atveju agentas tik UŽDUODAVO patikslinantį klausimą, NIEKADA nekviesdamas
   `calculate_savings_plan`. Dabar (YEARS/DATE/CHILD_AGE/WHEN visiems režimams) mėnesinė įmoka ar
   pasiekimo data VISADA apskaičiuojama, net jei pajamos nežinomos - tik `free_cash`/`share`/`fits`
   laukai lieka `null` (NE 0 - skirtumas tarp "nežinoma" ir "nulis"), su kvietimu "Jei norite
   sužinoti, ar tai telpa į biudžetą, nurodykite savo pajamas.". Pajamos VIS TIEK NIEKADA
   neimamos iš statistikos/spėjamos. Java: `SavingsPlanRequest.netMonthlyIncome()` tapo
   neprivalomas (A3 kontraktas), `GoalsEngine`/`Core`/`SavingsPlanService` null-propagacija per
   visą skaičiavimo grandinę. Python: `savings_tool.resolve_income()` grąžina `None` vietoj
   `MissingIncomeError`. `response_builder.py`/`system_prompt.py`/eval case'ai atitinkamai
   pakeisti (žr. žemiau, bug 2/eval punktus).
2. **Skaičiavimo bug'as (sugalvotos pajamos)**: žinutei "Noriu sutaupyti 8000 eurų per 4 metus
   kelionei." modelis paskaičiavo 8000/4=2000 ir panaudojo kaip MĖNESINES pajamas (×12) - LLM
   išsigalvojo pajamų skaičių iš tikslo sumos/termino. Pataisyta dviem lygiais: (a)
   `system_prompt.py` aiškiai draudžia išvesti pajamas iš tikslo sumos/termino/bet kokio kito
   skaičiaus, su TIKSLIU šiuo pavyzdžiu; (b) `savings_tool.py` heuristinė apsauga
   (`_income_looks_derived_from_goal`) - jei modelio nurodytas `net_monthly_income` sutampa su
   tikslo_suma/terminas (arba ×12) IR šis skaičius NEPASIRODO vartotojo žinutėje, metamas
   `HallucinatedIncomeError` (tool error, ne tylus praleidimas).
3. **Crash bug'as**: po kelių pokalbio posūkių atsakymas "agentas nepasiekiamas" -
   `anthropic.BadRequestError 400`, "Invalid signature in thinking block" - `HttpSession`
   laikoma istorija kaupė `thinking`/`tool_use`/`tool_result` blokus, o nukirpus (truncate) iki
   paskutinių N žinučių, `thinking` bloko pokalbio-susiejimo parašas (signature) tapo
   nebegaliojantis. Pataisyta: `main.py`'s `_agent_chat` tarp posūkių laikomoje istorijoje
   ("messages" - ta pati raktas, kurį Java nepakitusiai laiko `HttpSession`) palieka TIK
   vartotojo tekstą ir modelio GALUTINĮ tekstą kaip paprastus eilučių porų sąrašus - NIEKADA
   `thinking`/`tool_use`/`tool_result` blokų. `MAX_HISTORY_MESSAGES=20` (lyginis skaičius)
   garantuoja, kad pirmas elementas po nukirpimo visada yra "user", niekada "assistant"
   (Messages API reikalavimas). Jokio thinking-binding beta header'io, jokio `drop_block` - tiesiog
   niekada NEgrąžinamas prie modelio. Claude API klaidos `/internal/chat` kelyje dabar logina
   ERROR lygiu su `request_id`; vartotojui rodomas TIKSLUS tekstas "Įvyko klaida. Pabandykite dar
   kartą arba pradėkite naują pokalbį.".
   - **Šalutinis poveikis, pastebėtas įgyvendinant**: kadangi "messages" grąžinamame JSON'e dabar
     yra paprastas tekstas (ne Messages-API blokai), `run_eval.py --via-endpoint`'o senas
     `_reconstruct_tool_calls`/`_reconstruct_final_text_and_status` (kurie skaitydavo `tool_use`/
     `tool_result` blokus iš "messages") nebeturėjo iš ko juos atstatyti. Pašalinti; `_agent_chat`
     dabar grąžina `tool_calls`/`final_text`/`status`/`stop_reason` kaip SAVO atskirus JSON laukus
     (Decimal-saugiai, `json.dumps(default=str)` + `_coerce_decimal_strings` atstatymas
     via-endpoint pusėje) - `--via-endpoint` šiuos laukus skaito TIESIOGIAI, nebe iš "messages".
4. **Eval harness**: `expect_clarifying_question` pervardytas/perkurtas į `expect_income_omitted`
   (dabar reiškia "įrankis KVIEČIAMAS be pajamų", ne "modelis klausia vietoj skaičiavimo") -
   `score_missing_income_handled` pakeistas DVIEM funkcijomis
   (`score_income_omitted_handled`/`score_no_hallucinated_income`). Missing-income case'ų
   skaičius padidintas 2→4 (varijuojamos formuluotės, YEARS ir CHILD_AGE režimai). Naujas
   `multi_turn` grupės case'as (1, anon, `--via-endpoint` tik) - 6 posūkių pokalbis, siunčiamas per
   TĄ PATĮ sesijos cookie `EvalCase.conversation_turns` laukeliu, reprodukuojantis bug 3 formą
   (`run_eval._run_one_via_endpoint`). Iš viso 46 case'ai (buvo 45).
5. Testai: Java 358 / 0 nepavyko (be naujo case'o - testas pakeistas, ne pridėtas). Python 480
   passed / 1 skipped (buvo 479 - naujos hallucinacijos/income-omitted/multi_turn testų grupės
   kompensuoja pašalintų `score_missing_income_handled` testų pašalinimą).
6. `B7-staging-checklist.md` 4 skyriaus 5/5b atvejai perrašyti su NAUJA tikėtina elgsena (5: įmoka
   BE pajamų, tool VIS TIEK kviečiamas; 5b: pajamų nurodymas → aiškus telpa/netelpa). Pridėtas
   perspėjimas apie `~/atsidek` serveryje esantį PASENUSĮ checkout'ą (rankinių `docker compose`
   komandų iš ten nenaudoti; env pakeitimams pirmenybę teikti GitHub Actions "Re-run all jobs").

## Prieš produkciją (savininko 2026-11-16/2026-11-17 nurodymas — užfiksuota, bet NEIŠTAISYTA)

Septyni žinomi trūkumai, kuriuos savininkas sąmoningai nusprendė palikti VĖLESNIAM darbui, bet
reikalavo užfiksuoti čia, kad nepasimestų prieš produkcinį diegimą:

- **Pilnas eval'as prieš prod diegimą**: šis raundas patikrintas tik greitu eval'u
  (`runs-per-case 1`, su judge, komanda `43e0ab9`). Prieš produkcinį diegimą (ne tik staging)
  reikalingas PILNAS eval'as (`runs-per-case 3`, su judge, numatytasis `RUNS_PER_CASE`), kad
  pass rate būtų paskaičiuotas per keletą pakartojimų, ne vieną.
- **LLM įžanga nekartoja šablono patarimo**: patikrinti, ar modelio laisvas tekstas (įžanga/
  paaiškinimas) savais žodžiais nepakartoja `general_tip`/šablono teksto, kurį vartotojas jau
  matys po jo (dubliavimas, ne klaida, bet prastas skaitomumas).
- **Vartotojui nerodyti vidinio tier žodyno**: patikrinti, kad vartotojui matomame tekste niekur
  nerodoma "FREE"/"PREMIUM" (vidiniai `Tier` enum pavadinimai) — tik "nemokama versija"/"Premium".
- **WHEN režimas + pasiekimo data + "nerealus"**: kai WHEN režimui rodoma pasiekimo data
  (žr. B6 eval #11 patvirtinimo 2 punktą aukščiau), šalia nerašyti "tikslas nerealus" — tik
  "įmoka didelė, apsvarstykite mažesnę" (data jau pati save paaiškina, "nerealu" prieštarautų
  rodomai konkrečiai datai).

- **Anoniminio CTA užraktas už feature flag**: item 5 (eval fae9e00) pataisė TIK prisijungusio
  vartotojo "atnaujinkite iki Premium" CTA — jis dabar reikalauja, kad `premium` sekcija turėtų
  turinio. Anoniminio vartotojo "jei prisijungsite" CTA (kviečiantis tiesiog prisijungti, ne
  pirkti Premium) šiuo pataisymu NEBUVO apribotas ir šiuo metu NĖRA susietas su tuo, ar `premium`
  sekcija tuščia — reikia nuspręsti, ar jis TURĖTŲ priklausyti nuo to paties feature flag (šiuo
  metu `premium` visada tuščias, tad šis CTA vis tiek nežada nieko konkretaus, tik prisijungimą).
- **LT kalbos kokybė**: šablonų/system prompt'o lietuvių kalbos tikrinimas šiuo metu yra TIK
  rankinis (savininko pastebėjimai per gyvus eval'us, pvz. "mėn.." klaida). Reikia nuspręsti, ar
  prieš produkciją reikalingas atskiras kalbos vertintojas (deterministinis arba LLM-judge
  patikrinimas, panašus į `judge_rubric.py`), ar pakanka Sonnet modelio peržvelgti galutinį
  šabloninį tekstą prieš jo panaudojimą.
- **VDA (statistikos departamento) išlaidų duomenys vietoj iliustracinių**: `stat_defaults`
  lentelės "expenses" segmentų eilutės šiuo metu yra iliustracinės prielaidos (žr. B6 eval #11
  patvirtinimo 5 punktą aukščiau — ekstrapoliuotos iš mockup'o, `needs_review=true`). Prieš
  produkciją reikia pakeisti jas tikrais VDA (Valstybės duomenų agentūros) namų ūkių biudžetų
  tyrimo duomenimis su patikrinamu šaltiniu/data, kad šablonas galėtų rodyti "Statistika:
  šaltinis, metai" vietoj "Iliustracinė prielaida".

## B7 — BAIGTA (savininko patvirtinimas, 2026-11-17)

Savininkas rankiniu būdu patikrino staging'ą (`B7-staging-checklist.md` 4 skyrius, po "B7 realaus
staging patikrinimo pataisos" aukščiau) — veikia. B7 Definition of Done (vidutinė kombinuotos
užklausos kaina ir trukmė, žr. `PLAN-taupymas-agentas.md` B7 skyrių):

- **Trukmė**: ~5,8 s (n=1).
- **Kaina**: nematuota — `usage_logging.log_usage`'o `cost_cents` apvalino kiekvieną realų Haiku
  modelio pokalbio kainą žemyn iki 0 (viena kombinuota užklausa kainuoja gerokai mažiau nei 1 ct),
  tad šis skaičius buvo nenaudingas. Apvalinimo klaida pataisyta B8 fazėje (žr. žemiau, item 8) —
  `cost_cents` pakeistas `cost_micros` (sveikasis skaičius, USD milijonosios dalys), skaičiuojamu
  iš tikrų token'ų kainų su `Decimal`/`ROUND_HALF_UP`, kaip ir anksčiau, tik su žymiai didesne
  skiriamąja geba.

## B8 dalis — pasiruošimas produkcijai (pradėta, savininko instrukcija, 2026-11-17)

Nauja šaka `feat/taupymas-agentas-b8` (nuo `main`, po B7 realaus staging patikrinimo pataisų
sulietos). Planas: `PLAN-taupymas-agentas.md` B8 skyrius, 8 punktai, kiekvienas — atskiras commit
su testais (1-3 ir 2 sujungti į vieną commit'ą dėl bendrų failų — žr. jų commit'ų aprašymus git
istorijoje dėl priežasties). Item 5 (anoniminio limito pranešimas su kvietimu prisijungti,
prisijungusiems didesnis limitas) rastas JAU įgyvendintas B6.6/B7-wiring fazėse
(`AGENT_CHAT_ANON_LIMIT_REPLY`, `ChatLimiter` tier-based caps `main.py`'e) — patvirtinta esamais
testais `test_internal_chat_agent.py`'e, naujo kodo/commit'o nereikėjo. PR #57 (po 1-8 punktų)
atidarytas, sustota pagal instrukciją.

**Item 9 (papildytas po PR #57, 2026-11-17)**: savininkas, ruošiantis production diegimui,
sugriežtino `/opt/atsidek/staging-data` teises (`chown 10001:999`, `chmod 700`). Kitas diegimas
krito — `deploy/deploy.sh` kiekvieno diegimo metu PATS darė `mkdir -p && chmod 0777` ant
`DATA_DIR`, bet veikia kaip runner (uid 1000), kuris nebuvo nei root'as, nei katalogo savininkas,
tad `chmod` grąžino "Operation not permitted". Laikinas sprendimas (grąžinti `0777`) pakeistas
nuolatiniu: `deploy.sh` nebekeičia šio katalogo teisių — krenta su aiškiu pranešimu (su tikslia
`chown`/`chmod` komanda), jei katalogo nėra, ir perspėja (bet nekrinta), jei jo savininkas ne uid
10001. Katalogo paruošimas (vienkartinis root'o žingsnis) dokumentuotas
`PROD-checklist.md`'o naujoje "Vienkartinis serverio paruošimas" skiltyje.
