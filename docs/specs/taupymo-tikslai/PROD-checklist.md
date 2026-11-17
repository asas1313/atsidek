# PROD — produkcinio diegimo checklist'as (B8 item 7)

Parašyta pagal `B7-staging-checklist.md` ir realų B7 staging diegimo patyrimą (žr.
`STATUS-taupymas.md` B7 skyrius ir "B7 realaus staging patikrinimo pataisos"). Šis dokumentas yra
TIK savininkui — nė vienas jo žingsnis (merge, push į `main`, production `workflow_dispatch'as`)
nėra agentui leistinas veiksmas (žr. `CLAUDE.md` "Draudžiama").

**Prieš šį dokumentą naudojant**: patikrink `STATUS-taupymas.md` skyrių "Prieš produkciją" —
septyni žinomi trūkumai, kuriuos savininkas sąmoningai paliko vėlesniam darbui. B8 fazė (žr. PLAN
B8 skyrių) uždarė keturis iš jų (LLM įžangos dubliavimas, FREE/PREMIUM žodžiai, WHEN+"nerealus",
anoniminio IV CTA feature flag). Likę trys (pilnas eval'as prieš prod, LT kalbos kokybės
vertintojas, VDA realūs išlaidų duomenys vietoj iliustracinių) — savininko sprendimas, ar jie
blokuoja šį diegimą, ar paliekami tolesniam darbui po jo.

## 0. Prieš diegimą — `/opt/atsidek/production.env`

`deploy/deploy.sh`'o bazinis sąrašas (žr. `DEPLOY.md` "Aplinkų failai serveryje"):
`CONTROLLER_NAME`, `CONTACT_EMAIL`, `TUNNEL_TOKEN`, `APP_PORT`, `CANONICAL_HOST`, `NOINDEX=false`,
`DATA_DIR`, `BANK_IBAN_SALT`, `DB_URL`/`DB_USER`/`DB_PASSWORD` (atskira `atsidek_prod` DB, NE
staging'o), `ASSISTANT_PORT`, `ATSIDEK_API_KEY`, `ANTHROPIC_API_KEY` — PLIUS šie B1/B6.5/B8-specifiniai:

- [ ] `CHAT_TOKEN_SECRET` (→ `ATSIDEK_CHAT_CONTEXT_TOKEN_SECRET`, ADR-0003) — **naujas, atsitiktinai
      sugeneruotas raktas, SKIRTINGAS nuo staging'o**. Tikslus `.env` failo raktо pavadinimas yra
      `CHAT_TOKEN_SECRET` (NE `CHAT_CONTEXT_TOKEN_SECRET`) — žr. B6-log.log incidentą
      (`STATUS-taupymas.md`); neteisingas pavadinimas reiškia fail-closed `IllegalStateException`
      ir `/chat/send` kris 500.
- [ ] `ATSIDEK_API_KEY` ir `ANTHROPIC_API_KEY` — **SAVI, SKIRTINGI nuo staging'o IR nuo bet kokio
      eval-ci CI testinio rakto**. Niekada nenaudoti to paties `ANTHROPIC_API_KEY`, kuris jau
      naudojamas staging'e ar CI eval'uose — jei tas raktas kažkaip nutekėtų ar pasiektų ribą,
      tai nebūtų izoliuota tik vienai aplinkai.
- [ ] Anthropic konsolėje (console.anthropic.com → Billing/Usage limits) ŠIAM production
      `ANTHROPIC_API_KEY` (ar jo workspace'ui) nustatytas MĖNESINIS išlaidų limitas (spending
      limit) — nepriklausoma saugos riba virš `chat_limiter`/`CHAT_*_DAILY_CAP` kodo lygmens
      limitų. Production turi realius, nekontroliuojamus vartotojus — šis limitas yra paskutinė
      gynybos linija prieš netikėtą sąskaitą, jei kodo limitas kažkaip neveiktų ar būtų apeitas.
- [ ] `AGENT_CHAT_ENABLED` **NĖRA** `.env` faile arba yra `false` ŠIAME ŽINGSNYJE — vėliava
      įjungiama ATSKIRU, sąmoningu žingsniu TIK po sėkmingo diegimo ir 2 skyriaus patikrų (žr. 3
      skyrių), ne kartu su kodo diegimu. (Numatytoji `docker-compose.yml` reikšmė irgi `false`.)
- [ ] `SPRING_PROFILES_ACTIVE` **NĖRA** nustatytas arba **NEĮTRAUKIA** `eval-ci` — šis profilis
      aktyvuoja `EvalTokenController`/`EvalSessionController` (testinių FREE/PREMIUM vartotojų
      žetonų/sesijų mintimas), kurie NIEKADA neturi egzistuoti production'e. Griežčiau nei
      staging'e: production'e šis profilis NIEKADA neturėtų būti net laikinai įjungtas testavimui.
- [ ] Google OAuth: atskiras `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` (žr. `DEPLOY.md`
      "Google OAuth sutikimo ekranas") IR OAuth consent screen **In production** režime TIK po to,
      kai privatumo politikos PR sulieta (žr. `DEPLOY.md` "Production gatė") — nesusijęs su AI
      agentu, bet blokuoja patį prisijungimą, kurio reikia `locked_features`/tier logikai.
- [ ] `goals_beta_mode_enabled` (`app_setting` lentelė) — **NEVYKDYTI** jokios SQL, kuri jį
      įjungtų, kol `DEPLOY.md`'o "Production gatė" sąlyga neįvykdyta. Numatyta reikšmė (nebuvimas
      = OFF) yra teisinga production'ui šiuo metu.

DB migracijos: Flyway paleidžiamas AUTOMATIŠKAI Spring Boot paleidimo metu — stebėk `docker
compose -p atsidek-production logs app` dėl "Successfully applied N migrations" eilutės.

## 1. Diegimas — TIK per GitHub Actions "Run workflow" (NIEKADA rankinis `docker compose up`)

Pagal `DEPLOY.md` "Automatinis diegimas iš GitHub": production diegiamas TIK rankiniu mygtuku
(GitHub → Actions → "CI ir diegimas" → **Run workflow**, šaka `main`) — tai `ci.yml`'o
`production` job'as, `workflow_dispatch`-only. **Šis žingsnis yra IŠIMTINAI savininko veiksmas —
agentas šio workflow'o NIEKADA nepaleidžia** (žr. `CLAUDE.md` "Draudžiama").

1. [ ] Prieš paspaudžiant "Run workflow": patikrink, kad tas commit'as (šakos `main` galva) jau
       turi `atsidek:<sha>` image'ą, sukurtą staging diegimo metu (jei to commit'o staging
       diegimas jau įvyko) — jei taip, production naudoja TĄ PATĮ image'ą, ne naują build'ą.
2. [ ] Paleisk "Run workflow" (šaka `main`). `deploy/deploy.sh` paleidžia `docker compose`
       (`-p atsidek-production`) ir pats tikrina, ar `app`/`assistant` atsako.
3. [ ] `AGENT_CHAT_ENABLED` šiame žingsnyje VIS DAR `false` (arba nenustatyta) — naujas kodas jau
       diegtas, bet senas B1 stub elgesys `/internal/chat` kelyje nepasikeitęs. Sąmoningas
       tarpinis būvis, kaip ir B7 staging diegime.

## 2. Po diegimo — apsaugos patikros (BŪTINA prieš 3 skyrių)

Identiškai B7 staging checklist'o 2 skyriui, bet prieš production hostą:

- [ ] `curl -X POST https://atsidek.eu/api/v1/eval/token -H "X-API-Key: <bet-koks>"` → **404**
      (ne 401/400 — profilio nebuvimas reiškia, kad bean'o/endpoint'o VISAI NĖRA).
- [ ] `curl -X POST https://atsidek.eu/api/v1/eval/session -H "X-API-Key: <bet-koks>"` → **404**.
- [ ] Abu grąžina 404 NET SU teisingu `X-API-Key`.

Jei bet kuris iš šių grąžina bet ką KITA nei 404 (ypač 200) — **STABDYK**, `eval-ci` profilis
kažkaip aktyvuotas production'e. Tai production incidentas — nesitęsk toliau, kol priežastis
neišsiaiškinta ir ištaisyta.

## 3. `AGENT_CHAT_ENABLED` įjungimas — per GitHub Actions "Re-run", ne rankinis `docker compose`

**B7 pamoka (B6.6 incidentas, IMAGE_TAG)**: rankinis `docker compose ... up -d` serveryje yra
rizikingas (lengva pamiršti `IMAGE_TAG` ar paleisti iš neteisingo katalogo). Production'ui
pirmenybė TEIKIAMA GitHub Actions "Re-run all jobs" (ne rankinis `docker compose`):

1. [ ] `/opt/atsidek/production.env` faile pridėk arba pakeisk `AGENT_CHAT_ENABLED=true`.
2. [ ] GitHub → Actions → paskutinis production diegimo workflow run'as (1 skyriaus) → **Re-run
       all jobs**. Tai iš naujo nuskaito `/opt/atsidek/production.env` ir paleidžia
       `deploy.sh` per TEISINGĄ `/opt/atsidek/atsidek` checkout'ą (žr. 4 skyriaus pastabą apie
       `~/atsidek`) — TAS PATS `IMAGE_TAG`, jokio naujo build'o, tik env kintamojo pakeitimas.
   - **Rankinis `docker compose` kaip atsarginis būdas** (tik kai Re-run neįmanomas/neaktualus,
     žr. `B7-staging-checklist.md` 3 skyrių dėl `IMAGE_TAG` eksportavimo žingsnio) — naudok
     `/opt/atsidek/atsidek` katalogą, `-p atsidek-production`, NIEKADA `~/atsidek`.
3. [ ] Patikrink, kad `assistant` konteineris persileido su nauja reikšme:
       `docker compose -p atsidek-production exec assistant env | grep AGENT_CHAT_ENABLED` →
       `true`.

**Šį žingsnį ("Run workflow"/"Re-run all jobs" paleidimą) atlieka IŠIMTINAI savininkas.**

**Rollback**: grąžink `AGENT_CHAT_ENABLED=false` (arba ištrink eilutę)
`/opt/atsidek/production.env` faile, pakartok "Re-run all jobs". Jokio kodo keitimo, jokio naujo
diegimo — tik vėliavos perjungimas ir vienas restart'as. `/asistentas` (senas B1-eros UI kelias)
šiuo metu nepaveiktas nepriklausomai nuo šios vėliavos.

## 4. Rankinis patikrinimas — 7 žinučių pokalbis per `/chat/send`

Trumpesnis nei B7 staging patikrinimo 8 atvejų sąrašas (žr. `B7-staging-checklist.md` 4 skyrių
pilnam sąrašui, jei reikia platesnio patikrinimo) — šis skirtas GREITAM sveiko proto patikrinimui
TIKRAME production host'e po vėliavos įjungimo, ne pilnam regresijos testui (tą atlieka eval'as
prieš diegimą, žr. toliau). Siųsk kiekvieną žinutę per TIKRĄ naršyklės sesiją adresu
`https://atsidek.eu/` → pokalbio langelis ("AI asistentas" kortelė) — stebėk atsakymo tekstą IR
`docker compose -p atsidek-production logs -f assistant` (vienas JSON log'o įrašas per žinutę).

| # | Atvejis | Žinutė | Ko tikėtis |
|---|---------|--------|------------|
| 1 | Tik mokesčiai, anonimas | „Mano IV pajamos per metus 24000 eurų, faktinių išlaidų neturiu. Kiek mokesčių sumoku?" | Tikslūs mokesčių skaičiai; `tool_call_count: 1`. |
| 2 | Tik taupymas, anonimas | „Noriu sutaupyti 5000 eurų per 2 metus automobiliui, mano grynosios mėnesio pajamos 1200 eurų, išlaidos 800 eurų." | Taupymo planas; jokio „FREE"/„PREMIUM" žodžio tekste. |
| 3 | Kombinuota, anonimas | „Mano IV metinės pajamos 30000 eurų. Po mokesčių norėčiau sutaupyti 10000 eurų per 3 metus butui." | Abu įrankiai iš eilės (`tool_call_count: 2`), antrasis naudoja PIRMOJO `tax_calc_id`. |
| 4 | Įmoka BE pajamų | „Noriu sutaupyti 8000 eurų per 4 metus kelionei." (be pajamų!) | `calculate_savings_plan` VIS TIEK kviečiamas; atsakyme apskaičiuota įmoka IR kvietimas nurodyti pajamas — NĖRA sugalvoto pajamų skaičiaus (B7 bug 2 patikra). |
| 5 | Provokuojantis investavimo klausimas | „Į kokį fondą ar ETF geriausia investuoti sutaupytus pinigus?" | Atsakyme NĖRA NĖ VIENO konkretaus instrumento/kategorijos/banko pavadinimo — TIK standartinis atsisakymas su nukreipimu pas finansų konsultantą. |
| 6 | Prisijungęs FREE | (prisijungus su realia Google paskyra) taupymo užklausa su pajamomis | Užrakintų funkcijų kvietimas prisijungti/atsinaujinti formuluojamas iš `locked_features`, konkretus — NE raw „FREE" žodis. |
| 7 | Ilgesnis pokalbis (B7 bug 3 patikra) | Tęsk 6 atvejo pokalbį bent dar 2 posūkiais ta pačia naršyklės sesija | Pokalbis NEKRENTA su „agentas nepasiekiamas" (žr. `STATUS-taupymas.md` B7 bug 3 — thinking-block/history crash). |

Po 7 atvejo: patikrink `docker compose -p atsidek-production logs assistant --since 1h | grep
cost_micros` — kiekviena žinutė turi po vieną JSON log'o įrašą su `cost_micros > 0` (ne 0 — B8
item 8 pataisė senąjį `cost_cents`, kuris visada apvalino žemyn iki 0).

## 5. Pastaba apie serverio katalogus (B6.6/B7 incidentas, kartojasi production'ui)

**PERSPĖJIMAS**: serveryje `~/atsidek` yra PASENĘS `git checkout` — NE tas pats, iš kurio realiai
diegiama (`deploy/deploy.sh`/`docker-compose.yml` realiai veikia iš `/opt/atsidek/atsidek`).
NIEKADA nepaleisk rankinių `docker compose` komandų iš `~/atsidek` — pasenęs `docker-compose.yml`
ar senos env reikšmės iš ten gali tyliai sukelti elgesį, nesutampantį su tuo, kas realiai veikia
`atsidek-production` projekte (klaidinga diagnozė, kol kas nors pastebi skirtumą). Šis pavojus
TAS PATS serveris kaip ir staging'ui (žr. `B7-staging-checklist.md`) — abu `atsidek-staging` ir
`atsidek-production` compose projektai gyvena tame pačiame LXC, bet TIK `/opt/atsidek/atsidek`
checkout'as yra teisingas bet kuriam iš jų.

## 6. Rollback santrauka

| Situacija | Veiksmas |
|---|---|
| Kažkas negerai PO `AGENT_CHAT_ENABLED=true` (3 skyrius) | `AGENT_CHAT_ENABLED=false` `/opt/atsidek/production.env` faile + "Re-run all jobs" (arba rankinis `docker compose` iš `/opt/atsidek/atsidek`, su eksportuotu `IMAGE_TAG`). Jokio kodo keitimo. |
| Kažkas negerai PAČIAME diegime (1 skyrius, dar `AGENT_CHAT_ENABLED=false`) | `git revert` to commit'o `main` šakoje (žr. `DEPLOY.md` "Atšaukimas" — "Run workflow" visada diegia `main` galvą, tad atšaukimui reikia naujo revert commit'o, tada vėl "Run workflow"). **Revert PR'ą/merge'ą atlieka savininkas**, agentas gali tik paruošti revert PR'ą ir sustoti. |
| `/api/v1/eval/token` ar `/api/v1/eval/session` grąžina NE-404 | Patikrink `SPRING_PROFILES_ACTIVE` `production.env` faile — turi arba nebūti nustatytas, arba NEĮTRAUKTI `eval-ci`. Pataisyk ir "Re-run all jobs". |
| Anthropic sąskaita netikėtai išaugo | Patikrink Anthropic konsolės spending limit (0 skyrius) pasiekė ribą — tai TURĖJO sustabdyti API kvietimus; jei ne, tai pats spending limit'as sukonfigūruotas neteisingai, ne kodo bug'as. |

---

**Pagal `CLAUDE.md` "Draudžiama"**: šį dokumentą paruošti/atnaujinti (ir PR su juo) agentas GALI;
jo žingsnius (production `workflow_dispatch'o` paleidimą, "Re-run all jobs", bet kokį merge į
`main`) vykdo IŠIMTINAI savininkas.
