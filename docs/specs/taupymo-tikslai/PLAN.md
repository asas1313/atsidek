# PLAN.md – Taupymo tikslų skaičiuoklė su AI

> Pagal `docs/specs/taupymo-tikslai/spec.md` 8 skyrių. **Patvirtinta vartotojo – žr. 2 skyrių „Sprendimai“.** Vykdoma: F0 ∥ F1 ∥ F4 lygiagrečiai, po jų F2, tada F3, tada F5, tada F6.

## 0. Santrauka

Naujas modulis „Taupymo tikslai“ prisijungia prie esamo Atsidėk stack'o (Spring Boot + atskiras Python/FastAPI AI servisas), laikydamasis tų pačių principų kaip esama IV mokesčių skaičiuoklė + asistentas: **skaičiuoja tik Java, modelis tik ištraukia/paaiškina**. Prieš šį modulį repo pereina pilną migraciją nuo H2 prie tikro PostgreSQL (F0) – vartotojas patvirtino, kad du datasource'ai (H2 senoms lentelėms + Postgres naujai) nepriimtini.

## 1. Ką radau esamame repo (konvencijos, kurių laikomės)

- **Java paketai:** `eu.atsidek.engine` (grynas skaičiavimas, be Spring) + `eu.atsidek.web` (kontroleriai) – mažas modulis (mokesčiai); `eu.atsidek.bank.*` – didesnis modulis su subpaketais `web`, `store` (JdbcTemplate + `schema.sql`), `parse`, `classify`. Taupymo tikslai: `eu.atsidek.goals` (variklis), `eu.atsidek.goals.web` (kontroleriai), `eu.atsidek.goals.stats` (DB).
- **DB (prieš F0):** `schema.sql` + `spring.sql.init.mode=always`, `JdbcTemplate`, realiai – H2 failas `MODE=PostgreSQL`. **Po F0:** tikras PostgreSQL, Flyway migracijos.
- **API raktas:** `ApiKeyFilter` uždaro **visą** `/api/**` be `X-API-Key`/`Bearer`. Esamas modelis: viešas `POST /calculate` (htmx, be rakto) šalia `POST /api/v1/compare` (su raktu, serveris-serveriui). Tą patį dvigubą maršrutą kartojame.
- **Python servisas:** `assistant/` (FastAPI, `create_app()`, DI konstruktoriuje testams) – produkcinis. `ai/` – mokymosi prototipas, ne gamybinis kodas.
- **Claude naudojimas:** priverstinis `tool_use`, griežta JSON schema, normalizavimas Python kode, modelis `claude-haiku-5-5` per `AI_MODEL` env.
- **Nav:** vienas bendras fragmentas `bank/fragments.html :: nav`. Pridedame tik punktą „Taupymo tikslai“, likusios struktūros nekeičiame.
- **CI:** `mvn test` + `pytest` ant `ubuntu-latest` (GitHub-hosted, Docker prieinamas iš karto – tinka Testcontainers); diegimas (`staging`/`production`) – atskiri job'ai ant `self-hosted` runner'io, tik po `test`+`assistant-test`.
- **Testai:** Java – JUnit, Python – `pytest` + `TestClient`, fake'ai per Protocol.

## 2. Sprendimai (patvirtinta vartotojo, nebe atviri klausimai)

### K1 – PostgreSQL visai aplikacijai (ne tik `stat_defaults`)
- **Nauja fazė F0** „Migracija į PostgreSQL“, lygiagrečiai su F1/F4. F2 prasideda tik po F0 merge.
- Staging ir prod Postgres jau veikia atskirame LXC: `192.168.68.106:5432`, duomenų bazės `atsidek_staging` ir `atsidek_prod`, atskiri vartotojai, prieiga leidžiama tik iš `192.168.68.104` (konteinerių išeinantis IP).
- `docker-compose.yml` Postgres servisas – **tik lokaliam dev**, niekada staging/prod (tie jungiasi į LXC per env kintamuosius).
- Prisijungimo duomenys – tik per `SPRING_DATASOURCE_URL`/`USERNAME`/`PASSWORD` env, niekada repo.
- **Flyway** – patvirtinta nauja priklausomybė. Esamas `schema.sql` → `V1__init.sql` (ar panašiai), naujos `stat_defaults` – kita migracija, seed – atskira migracija su `ON CONFLICT`.
- Testai – **Testcontainers** (`testcontainers-postgresql`, test scope). `mvn test` CI job'as veikia ant `ubuntu-latest`, kur Docker yra iš karto – Docker-in-Docker patikrinimas aktualus tik jei kas nors testus bandytų leisti per `self-hosted` runner'į (šiandien nebando). **Jei paaiškėtų, kad Testcontainers CI'uje neveikia dėl bet kokios priežasties – F0 agentas STABDO ir klausia vartotojo; H2 fallback be sutikimo draudžiamas.**
- Duomenų perkėlimo H2 → Postgres skriptas + instrukcija `DEPLOY.md`. Vykdymas prieš realius staging/prod duomenis – **tik su tiesiogine prieiga prie serverio/tinklo**; jei vykdantis agentas tokios prieigos neturi, jis paruošia skriptą ir dokumentaciją, bet realaus vykdymo prieš staging/prod DB neatlieka – apie tai praneša.

### K2 – Viešas `/goals/*` + raktu apsaugotas `/api/v1/goals/*`, viena servisų klasė
- Patvirtinta kaip siūlyta. Kelio pavadinimų iš spec 4 skyriaus **nekeičiame** – Caddy path matcher'iai palaiko tikslius kelius, jokio pervadinimo rizikos nebėra.
- Python `/api/v1/goals/parse` ir `/api/v1/goals/explain`: **be** esamo per-IP `RateLimiter` (tas liko tik `/asistentas` IV mokesčių srautui). Vietoj jo – paprastas **globalus dienos limitas** Claude kvietimams, konfigūruojamas `GOALS_AI_DAILY_LIMIT` (numatyta 500). Viršijus – atsakymas sako, kad AI laikinai neprieinamas, **forma veikia toliau be AI**.

### K3 – JSON + vanilla JS, grafikas – rankomis rašytas inline SVG
- Patvirtinta. Jokių naujų JS bibliotekų (nei grafikui, nei kitur).

### K4 – Seed su `is_illustrative=true`, tikrų reikšmių patikrinimas – atskira F2 užduotis
- Patvirtinta kaip siūlyta (žr. 1 skyriaus ankstesnę versiją dėl surastų šaltinių: `osp.stat.gov.lt` darbo užmokesčio/pajamų puslapiai, `lb.lt/lt/indeliu-palukanu-normos`). F2 agentas atidaro portalus tiesiogiai (ne per paiešką) ir patikslina, kur įmanoma; nepatikrintos reikšmės lieka `is_illustrative=true`.

### K5 – Grąžos/infliacijos numatytosios: PRIELAIDOS, ne statistika
- MVP numatytosios: **infliacija 2,5 %, grąža 3 %**. Tai ilgalaikės modelio prielaidos, ne trumpalaikė LB prognozė – **nenaudojame** svyruojančių LB ketvirtinių prognozių (K4 tyrime rastos 3,1–5,1 % reikšmės) numatytosioms.
- Naujas, **4-as kilmės tipas: `ASSUMPTION`** (šalia `USER`/`AI`/`STATISTICS`). UI ženkliukas – neutrali pilka spalva (ne `STATISTICS` mėlyna). Šaltinio laukas rodo vieno sakinio paaiškinimą (pvz. „Ilgalaikė modelio prielaida, ne statistinė prognozė“), **be** `source_url`/`as_of` (tai ne duomenų šaltinis).
- `stat_defaults` lentelėje `annualInflation`/`annualReturn` arba lieka joje su `is_illustrative=true` ir aiškiu `source='Prielaida'`, arba (geriau) **nededami į `stat_defaults` iš viso**, o užkoduojami kaip konstantos `GoalsEngine`/konfigūracijoje su `origin=ASSUMPTION` – F1 agentas pasirenka švaresnį variantą ir užrašo PR apraše, kodėl.

### Kiti sprendimai
- **Spalvos:** NE atskira `--goals-*` paletė. Naujas puslapis naudoja esamus `style.css` kintamuosius (`--bg`, `--card`, `--fg`, `--accent`, `--muted`...). Pridedami **tik** 4 kilmės ženkliukų stiliai (USER/AI/STATISTICS/ASSUMPTION) – nauji, siauros apimties CSS klasės/kintamieji tik ženkliukams, ne visam puslapiui.
- **Nav:** tik pridėti „Taupymo tikslai“ punktą į `bank/fragments.html :: nav`.
- **Eval'ai:** `assistant/evals/goals_parse/`. Įprastame CI (kiekvienas PR) – **tik „smoke“ testai su fake Claude klientu** (keli pavyzdžiai, jokio tikro API kvietimo). Pilni eval'ai (≥60 formuluočių, tikras API) – **atskiras workflow**, paleidžiamas `workflow_dispatch` arba PR žyme `run-evals`; leidžiama paleisti tik keičiant promptą, schemą, modelį ar normalizavimo kodą.

## 3. Architektūra

```
                    ┌─ POST /goals/calculate  (viešas, JSON) ─────┐
Naršyklės JS ───────┤                                              ├──► GoalsEngine (eu.atsidek.goals)
(forma, debounce)   └─ GET  /goals/stats/defaults (viešas, JSON) ──┘         │
                                                                              ▼
                    ┌─ POST /api/v1/goals/calculate (API raktas) ─┐   StatDefaultsService
Python assistant/ ──┤                                              ├──► (eu.atsidek.goals.stats, JdbcTemplate)
(/explain srautas)  └─ GET  /api/v1/goals/stats/defaults (raktas) ─┘         │
                                                                              ▼
                                                                    stat_defaults (PostgreSQL, Flyway, po F0)

Naršyklės JS ──► POST /api/v1/goals/parse (Python, viešas, globalus dienos limitas)
             ──► POST /api/v1/goals/explain (Python, viešas, globalus dienos limitas) ──► Java /api/v1/goals/calculate (raktas)
```

Kilmė (`origin`) kiekvienai reikšmei: `USER` › `AI` › `STATISTICS` › `ASSUMPTION` (prioriteto tvarka naudojimui; UI ženkliukas rodo faktinę kilmę, ne prioritetą).

Nauji Java paketai:
- `eu.atsidek.goals` – `GoalType`, `GoalMode`, `Origin` (su `ASSUMPTION`), `Verdict`, `Household`, `Assumptions`, `GoalInput`, `SeriesPoint`, `GoalResult`, `GoalsEngine`.
- `eu.atsidek.goals.web` – `GoalsController` (viešas `/goals/**`), `GoalsApiController` (`/api/v1/goals/**`).
- `eu.atsidek.goals.stats` – `StatDefaultsStore` (JdbcTemplate), `StatDefaultsService`.

Nauji Python moduliai (`assistant/app/`):
- `goals_extractor.py`, `goals_calculator.py`, `goals_limiter.py` (globalus dienos limitas, ne per-IP), nauji maršrutai `main.py`.

## 4. Fazės

Kiekviena fazė – atskira šaka iš `main`, atskiras PR, testai žali prieš PR, PR aprašyme – ką/kodėl/kaip testuota + code review iš kito subagento. Jokių paslapčių repo. **Jokia fazė nemerge'ina savo PR į `main` pati ir nediegia į production.**

### F0 – Migracija į PostgreSQL
*Priklausomybės: nėra. Lygiagrečiai su F1, F4.*
1. `pom.xml`: pašalinti H2, pridėti `postgresql` (runtime) + `flyway-core` + `flyway-database-postgresql` (jei reikalingas atskiras artifact'as šiai Flyway versijai) + `testcontainers`/`testcontainers-postgresql` (test scope).
2. `src/main/resources/schema.sql` → `src/main/resources/db/migration/V1__init.sql` (Flyway numatytasis katalogas), turinys – esamas `schema.sql` pritaikytas PostgreSQL sintaksei (H2 `GENERATED BY DEFAULT AS IDENTITY` → Postgres atitikmuo, tipai ir pan.). Esama vienkartinė `psd_from_2026` migracija lieka SQL duomenų migracijos dalimi arba tampa atskira `V2` migracija.
3. `application.yml`: `spring.datasource.url/username/password` – tik iš env (`SPRING_DATASOURCE_*`), pašalinti H2-specifinę numatytąją reikšmę; Flyway įjungtas pagal numatytuosius Spring Boot nustatymus (`spring.flyway.*` jei reikia override).
4. `docker-compose.yml`: naujas `postgres` servisas **su aiškiu komentaru „tik lokaliam dev“**; `app` serviso env numatytosios reikšmės nukreiptos į šį lokalų servisą tik dev kontekste (ne staging/prod – ten env ateina iš deploy aplinkos, ne iš compose numatytųjų).
5. `pom.xml`/surefire – pašalinti H2 `spring.datasource.url` override (`jdbc:h2:mem:test...`); testai naudoja Testcontainers arba Spring testinį profilį su realiu Postgres konteineriu.
6. Duomenų migracijos skriptas (H2 failas → Postgres) + instrukcija `DEPLOY.md` naujame skyriuje. Vykdymas prieš staging – tik jei agentas turi tinklo/serverio prieigą; jei ne, paruošti ir aiškiai pažymėti, kad lauks žmogaus vykdymo.
7. `.github/workflows/ci.yml`: patikrinti, ar `mvn test` (ubuntu-latest) sėkmingai pakelia Testcontainers Postgres be papildomų nustatymų; jei reikia – pridėti `services:`/Docker nustatymus.
8. **Testai:** visi esami Java testai (bank, auth, tax) toliau žali su Postgres/Testcontainers vietoj H2 – tai šios fazės svarbiausias patikrinimas (regresija draudžiama).
9. PR aprašas: aiškiai surašyti, kas pasikeitė diegimo procedūroje (naujas env kintamųjų rinkinys, Flyway migracijų tvarka, kaip atstatyti staging/prod jei migracija nepavyks).

### F1 – Skaičiavimo variklis + `/calculate` (Spring Boot)
*Priklausomybės: nėra. Lygiagrečiai su F0, F4. Nelaukia F0 – nieko neliečia DB srityje.*
1. `eu.atsidek.goals`: `GoalType`, `GoalMode`, `Origin` (`USER`, `AI`, `STATISTICS`, `ASSUMPTION`), `Verdict`; `Household`, `Assumptions`, `GoalInput`, `SeriesPoint`, `GoalResult`.
2. `GoalsEngine` pagal spec 3 formules: `rm`, `FV(k)`, `B(P,k)`, `P` pagal režimą, `n` pagal režimą (minimum 12, `targetAge>currentAge`), `WHEN` paieška `k∈[1,600]` (nerasta → `reachable=false`), `paid`, `gain`, `share`, vertinimo ribos, `series[]` (≤24 taškai, praretinimas). `BigDecimal`, `HALF_UP` iki centų.
3. Numatytosios `annualInflation=2.5`, `annualReturn=3` su `origin=ASSUMPTION` – įgyvendinti pagal K5 pasirinktą variantą (konstanta kode arba `stat_defaults` įrašas su `source='Prielaida'`); PR apraše paaiškinti pasirinkimą.
4. `eu.atsidek.goals.web.GoalsController` (viešas `/goals/calculate`) + `GoalsApiController` (`/api/v1/goals/calculate`, automatiškai po `ApiKeyFilter`, nes kelias `/api/**`). Viena bendra servisų/validacijos klasė, du ploni kontroleriai.
5. Klaidų pranešimai lietuviškai (kaip `TaxApiController.checkAmount`).
6. `SecurityConfig`: pridėti `/goals/**`, `/api/v1/goals/**` į `permitAll()` sąrašą (API raktas vis tiek filtruoja `/api/**` per `ApiKeyFilter`, tai nepriklauso nuo `authorizeHttpRequests`).
7. **Testai:** vienetiniai kiekvienam režimui, kraštutiniai atvejai (`r=0`, `i=0`, `n=12`, nepasiekiamas `WHEN`, `free<=0`), ≥3 rankiniu būdu patikrinti pavyzdžiai (makete pateiktas CHILD_AGE pavyzdys – orientacinis, patikrinti tiksliai, ne aklai kopijuoti suapvalintus skaičius). Kontrolerio testai kaip `TaxApiControllerTest`.
8. Šis PR **neturi** priklausyti nuo F0 (naudoja esamą DB konfigūraciją, jei kokia nors prireiktų – bet `GoalsEngine` yra gryna logika be DB). Jei F0 merge'inamas pirmiau, rebase ant jo; jei vėliau – F1 merge'inamas į esamą (H2) `main` be konflikto, nes neliečia `schema.sql`/`pom.xml`.

### F2 – Statistikos lentelė, seed, `/stats/defaults`
*Priklausomybės: F0 (merge'intas į `main`).*
1. Flyway migracija: `stat_defaults` lentelė (`key`, `segment`, `value`, `unit`, `source`, `source_url`, `as_of`, `is_illustrative`), komentaras, kad planinis atnaujinimo job'as – ne MVP, bet suprojektuota jam.
2. Seed migracija (`ON CONFLICT DO NOTHING`/`DO UPDATE`): pajamos/išlaidos pagal `members`/`workers` segmentus, tipinė suma pagal `goalType`. **Be** `annualInflation`/`annualReturn`, jei F1 pasirinko konstantų variantą (K5).
3. Realių reikšmių patikrinimas: atidaryti `osp.stat.gov.lt` (darbo užmokestis, namų ūkių biudžetų tyrimas) ir `lb.lt` tiesiogiai, surašyti konkrečią lentelę/datą kiekvienai reikšmei; kas nepatikrinta – lieka `is_illustrative=true`.
4. `eu.atsidek.goals.stats.StatDefaultsStore` (JdbcTemplate) + `StatDefaultsService` (segmento parinkimas pagal narius/dirbančius/regioną/tikslo tipą, su fallback'u).
5. `GET /goals/stats/defaults` (viešas) + `GET /api/v1/goals/stats/defaults` (raktas).
6. **Testai:** servisas (segmento parinkimas, fallback'ai), repository per Testcontainers.

### F3 – UI su forma (be AI), prijungta prie F1–F2
*Priklausomybės: F1, F2.*
1. Puslapis `taupymo-tikslai` (Thymeleaf, `GET /taupymo-tikslai`), nuoroda pridėta į `bank/fragments.html :: nav`.
2. Forma pagal maketą: tikslo kortelės, sumos slankiklis, 4 režimų jungiklis su sąlyginiais laukais, namų ūkio stepperiai/slankikliai, prielaidų slankikliai. Tikri `<label>`, elementai ≥44px.
3. Rezultatų kolona: pagrindinė kortelė, 4 mini kortelės, vertinimo juosta, **ranka rašytas inline SVG** stulpelinis grafikas (be bibliotekų), „Prielaidos ir šaltiniai“ su ženkliukais (4 kilmės tipai, žr. 2 skyrių), disclaimer.
4. Vanilla JS: debounce ~300 ms, kviečia `/goals/calculate` + `/goals/stats/defaults`; rankiniu būdu keista reikšmė → `origin=USER`.
5. Mobilus išdėstymas.
6. **Testai:** kontrolerio testas; **privalomas rankinis patikrinimas realiame naršyklėje prieš PR** (golden path + kraštutiniai atvejai + mobilus vaizdas).

### F4 – Python `/parse` + eval rinkinys
*Priklausomybės: nėra. Lygiagrečiai su F0, F1.*
1. `assistant/app/goals_extractor.py`: `extract_goal` įrankis, schema = `calculate` laukai + `confidence` kiekvienam; normalizavimas kode („20 tūkst.“→20000, „pilnametis“→18, „iki vasaros“→data su einamųjų metų kontekstu).
2. `assistant/app/goals_limiter.py`: globalus dienos limitas (`GOALS_AI_DAILY_LIMIT`, numatyta 500), **ne** per-IP, atskirai nuo esamo `limiter.py`.
3. `POST /api/v1/goals/parse`: laukai + `confidence` + `missingCritical[]` + `clarifyingQuestion` (vienas, 2–4 variantai arba `null`). Klausimas tik kai trūksta `amount`/termino arba `confidence<0.6`. Viršijus dienos limitą – aiški žinutė, kad AI laikinai neprieinamas.
4. `evals/goals_parse/` → **`assistant/evals/goals_parse/`**: ≥60 lietuviškų formuluočių (be diakritikų, su klaidomis, šnekamąja kalba, keli vaikai, „kada turėsiu...“, neaiškūs atvejai) + laukiamas JSON. Scoring skriptas (tikslumas per lauką + bendras), rezultatas → `assistant/evals/results/` su data ir modelio pavadinimu.
5. **CI:** tik „smoke“ testai (fake Claude klientas, keli pavyzdžiai) `assistant-test` job'e. Atskiras workflow (`workflow_dispatch` arba PR žyma `run-evals`) pilniems eval'ams su tikru API – naudoti tik keičiant promptą/schemą/modelį/normalizavimo kodą.
6. Laisvas tekstas į logus nerašomas – tik ištrauktas JSON.
7. PR apraše – trumpas „kaip tai veikia“ paaiškinimas.

### F5 – `/explain` + AI lauko integracija UI
*Priklausomybės: F3, F4.*
1. `assistant/app/goals_calculator.py` – HTTP klientas į `POST /api/v1/goals/calculate` (API raktas, kaip `AtsidekApi`).
2. `POST /api/v1/goals/explain`: `calculate` atsakymas → `{text, suggestions[]}` su vietos žymėmis, skaičius įterpia kodas.
3. **Testas:** paaiškinimo tekste nėra skaičiaus, kurio nėra `calculate` atsakymo JSON.
4. UI: AI textarea, 2 pavyzdžių mygtukai, „Skaičiuoti su AI“, „AI suprato: ...“ lustai, `clarifyingQuestion`, AI paaiškinimo blokas. Viršijus `GOALS_AI_DAILY_LIMIT` – forma lieka naudojama be AI.
5. Klaidos atveju (API neprieinamas/netinkamas JSON) – forma veikia be AI.

### F6 – Deploy į staging, dokumentacija, vartotojo gidas
*Priklausomybės: F1–F5 sujungti (ir F0 jau seniai merge'intas prieš F2).*
1. `Caddyfile`: `/api/v1/goals/parse`, `/api/v1/goals/explain` → `assistant:8000`; `/goals/**`, `/api/v1/goals/calculate`, `/api/v1/goals/stats/defaults` → `app:8080`. Tikslūs keliai – jokio pervadinimo.
2. `DEPLOY.md`: Postgres LXC (`192.168.68.106`) prisijungimo duomenų tvarkymas per env staging/prod aplinkoms, Flyway migracijų eiga diegiant, H2→Postgres duomenų migracijos paleidimo instrukcija (jau parašyta F0, čia – galutinis patikrinimas/papildymas naujom lentelėm).
3. `.github/workflows/ci.yml`: patikrinti, ar reikia pridėti `run-evals` workflow (F4 punktas).
4. `docs/` – techninė dokumentacija + trumpas vartotojo gidas lietuviškai.
5. `README.md` – naujo modulio aprašymas.
6. Diegimas – **tik staging**, per esamą CI (merge į `main`). **Production diegimo jokia fazė nedaro.**

## 5. Rizikos

- **F0 apimtis didelė** (visos esamos lentelės, ne tik naujos) – didžiausia regresijos rizika; F0 PR privalo turėti pilną esamų testų (bank/auth/tax) rinkinį žalią su Postgres.
- **Testcontainers CI:** tikimasi, kad `ubuntu-latest` GitHub-hosted runner'yje veiks be papildomo setup; jei ne – F0 stabdo ir klausia, be H2 fallback.
- **Realaus staging/prod duomenų perkėlimo vykdymas** – agentas greičiausiai neturės tinklo prieigos prie `192.168.68.106`; F0 rezultatas gali būti tik paruoštas skriptas + instrukcija, realus vykdymas lieka žmogui.
- **Eval'ų kaina:** apribota atskiru workflow, kad nebrangintų kiekvieno PR.
- **K5 įgyvendinimo detalė** (konstanta vs `stat_defaults` įrašas) paliekama F1 agento sprendimui su PR paaiškinimu – nedidelė rizika, kad F2 teks prisiderinti prie F1 pasirinkimo (todėl F2 priklauso nuo F1 bent šioje detalėje, nors formaliai priklauso tik nuo F0 – F2 agentas prieš pradėdamas peržiūri F1 PR, jei jau sujungtas).

## 6. Definition of Done → fazių atitikmuo

| DoD (spec 9) | Faze |
|---|---|
| Visi 4 režimai veikia staging, sutampa su rankiniais pavyzdžiais | F1, F6 |
| Kiekviena reikšmė turi kilmės ženkliuką (4 tipai); statistinės – šaltinį ir datą | F1, F2, F3 |
| AI ištraukimo eval'ų tikslumas ≥90 % pagrindiniams laukams | F4 |
| Paaiškinime nėra „haliucinuotų“ skaičių (testas) | F5 |
| Forma veikia be AI | F3 |
| Mobilus vaizdas tvarkingas | F3 |
| Dokumentacija ir vartotojo gidas | F6 |
| Pilna migracija į PostgreSQL be regresijos | F0 |

---

Vykdoma tvarka: **F0 ∥ F1 ∥ F4** dabar, atskirose šakose/worktree, kiekvienas su testais ir PR į `main`. F2 startuoja po F0 merge. F3 – po F1+F2. F5 – po F3+F4. F6 – po visų.
