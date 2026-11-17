# B7 — staging diegimo checklist'as

Parašyta pagal TIKRĄ B6.5/B6.6/B7 elgseną (žr. `PLAN-taupymas-agentas.md` B6.5/B6.6 fazes):
`/internal/chat` dabar realiai kviečia `run_agent_loop` (ne B1-eros stub'ą) kai
`AGENT_CHAT_ENABLED=true`; TA PATI vėliava (B6.6) rodo pagrindiniame puslapyje ("/") realų pokalbio
langelį (vietoje seno `/asistentas` formos) - 4 skyriaus rankinis patikrinimas vyksta TEN, ne
kažkokiame kitame, neegzistuojančiame puslapyje. Rollback yra TIK vėliavos perjungimas (+
`IMAGE_TAG`, žr. 3/6 skyrius), ne kodo keitimas.

**Sustok prieš diegimą šio dokumento pabaigoje nurodytuose žingsniuose — savininkas pats paleis
greitą eval'ą su `--via-endpoint` prieš bet kokį realų staging diegimą.**

**PERSPĖJIMAS apie serverio katalogus (owner pastaba, 2026-11-17):** serveryje `~/atsidek` yra
PASENĘS `git checkout` — NE tas pats, iš kurio realiai diegiama (`deploy/deploy.sh`/
`docker-compose.yml` realiai veikia iš `/opt/atsidek/atsidek`, žr. 3 skyriaus `IMAGE_TAG` žingsnį).
NIEKADA nepaleisk rankinių `docker compose` komandų iš `~/atsidek` — pasenęs `docker-compose.yml`
ar senos env reikšmės iš ten gali tyliai sukelti elgesį, nesutampantį su tuo, kas realiai veikia
`atsidek-staging` projekte (klaidinga diagnozė, kol kas nors pastebi skirtumą). Bet kokiam ENV
KINTAMOJO pakeitimui (pvz. `AGENT_CHAT_ENABLED`, `CHAT_ANON_DAILY_CAP`) PIRMENYBĘ teikti GitHub
Actions "Re-run all jobs" (CI `staging` job'as iš naujo nuskaito `/opt/atsidek/staging.env` ir
paleidžia `deploy.sh` per TEISINGĄ `/opt/atsidek/atsidek` checkout'ą) — šio dokumento 3 ir 9
skyrių rankinės `docker compose` komandos (kurios jau naudoja TEISINGĄ `/opt/atsidek/...` kelią,
NE `~/atsidek`) lieka kaip dokumentuotas atsarginis būdas, kai CI re-run neįmanomas/neaktualus.

## 0. Prieš diegimą — ko reikia `/opt/atsidek/staging.env`

`deploy/deploy.sh`'o paties sąrašas (CONTROLLER_NAME, CONTACT_EMAIL, TUNNEL_TOKEN, APP_PORT,
CANONICAL_HOST, NOINDEX, DATA_DIR, BANK_IBAN_SALT, DB_URL, DB_USER, DB_PASSWORD, ASSISTANT_PORT,
ATSIDEK_API_KEY, ANTHROPIC_API_KEY) PLIUS šie B1/B6.5-specifiniai:

- [ ] `CHAT_TOKEN_SECRET` nustatytas (→ `ATSIDEK_CHAT_CONTEXT_TOKEN_SECRET`, ADR-0003 kontekstinio
      žetono HMAC paslaptis). **Tikslus pavadinimas** — žr. B6-log.log incidentą
      (`STATUS-taupymas.md`): `CHAT_TOKEN_SECRET`, NE `CHAT_CONTEXT_TOKEN_SECRET` šiame `.env`
      faile (docker-compose pats prideda `ATSIDEK_CHAT_` priešdėlį) — jei pavadinimas neteisingas,
      `ContextTokenService` fail-closed meta `IllegalStateException`, ir `/chat/send` kris 500.
- [ ] `ATSIDEK_API_KEY` ir `ANTHROPIC_API_KEY` (Python pusei — `docker-compose.yml`'o
      `assistant` servisas) nustatyti ir **SKIRIASI** nuo bet kokio eval-ci CI testinio rakto.
- [ ] Anthropic konsolėje (console.anthropic.com → Billing/Usage limits) šiam `ANTHROPIC_API_KEY`
      (ar jo workspace'ui) nustatytas MĖNESINIS išlaidų limitas (spending limit) — nepriklausoma
      saugos riba virš `chat_limiter`/`AI_DAILY_CAP` kodo lygmens limitų, apsauganti nuo
      netikėtos sąskaitos, jei kodo limitas kažkaip neveiktų arba būtų apeitas.
- [ ] `AGENT_CHAT_ENABLED` **NĖRA** `.env` faile arba yra `false` ŠIAME ŽINGSNYJE — vėliava
      įjungiama ATSKIRU, sąmoningu žingsniu po sėkmingo diegimo (žr. 3 skyrių), ne kartu su kodo
      diegimu. (Numatytoji `docker-compose.yml` reikšmė irgi `false`, jei šios eilutės visai nėra.)
- [ ] `SPRING_PROFILES_ACTIVE` **NĖRA** nustatytas arba **NEĮTRAUKIA** `eval-ci` — šis profilis
      aktyvuoja `EvalTokenController`/`EvalSessionController` (testinių FREE/PREMIUM vartotojų
      žetonų/sesijų mintimas), kurie NIEKADA neturi egzistuoti staging/production aplinkoje.

DB migracijos: Flyway paleidžiamas AUTOMATIŠKAI Spring Boot paleidimo metu (jokio atskiro rankinio
žingsnio) — stebėk `docker compose -p atsidek-staging logs app` dėl `Successfully applied N
migrations` eilutės po diegimo.

## 1. Diegimas

Merge tvarka: B3 → B4 → B5 → B6 → B7-wiring. B3–B6 jau sumerge'inti į `main` kaip vienas
konsoliduotas PR #53 (`a849862`, 2026-11-17) — individualios B3/B4/B5 PR (#44/#45/#46) uždarytos
kaip nebeaktualios, nes jų turinys jau yra `main`'e (žr. STATUS-taupymas.md "Atsitiktinis main
sujungimas"). Taigi šiame žingsnyje liko sulieti TIK B7-wiring PR.

1. [ ] Sulieti B7-wiring PR į `main` (žr. PR aprašymą dėl CI/eval statuso).
2. [ ] `ci.yml`'o `staging` job'as automatiškai paleidžiamas `push`'o į `main` metu (ne
       `workflow_dispatch'as` — tas tik production'ui) — stebėk GitHub Actions eigą
       (`gh run watch` arba UI). **Kiekvienas** push į `main` (ne tik šis) automatiškai paleidžia
       staging diegimą — tai saugu, kol `AGENT_CHAT_ENABLED=false` (senas B1 kelias nepakeistas),
       bet reiškia, kad po šio sujungimo bet kuris tolesnis push į `main` vėl išdiegs staging'ą be
       papildomo patvirtinimo.
3. [ ] Po sėkmingo `deploy/deploy.sh staging <sha>`: ir `app` (:`APP_PORT`), ir `assistant`
       (:`ASSISTANT_PORT`) `/healthz`/`/` atsako (script'as pats tai patikrina, bet patikrink dar
       kartą rankiniu `curl`, jei netiki automatika).
4. [ ] `AGENT_CHAT_ENABLED` šiame žingsnyje VIS DAR `false` (arba nenustatyta) — naujas kodas jau
       diegtas, bet senas B1 stub elgesys `/internal/chat` kelyje nepasikeitęs. Tai sąmoningas
       tarpinis būvis, ne klaida.

## 2. Po diegimo — apsaugos patikros (BŪTINA prieš 3 skyrių)

- [ ] `curl -X POST https://<staging-host>/api/v1/eval/token -H "X-API-Key: <bet-koks>"` →
      **404** (ne 401/400 — profilio nebuvimas reiškia, kad bean'o/endpoint'o VISAI NĖRA, žr.
      `EvalTokenServiceProfileGatingTest`/`EvalTokenServiceStagingProfileGatingTest`).
- [ ] `curl -X POST https://<staging-host>/api/v1/eval/session -H "X-API-Key: <bet-koks>"` →
      **404** (naujas B6.5 endpoint'as, ta pati `eval-ci`-profilio apsauga — žr.
      `EvalSessionServiceProfileGatingTest`/`EvalSessionServiceStagingProfileGatingTest`).
- [ ] Abu grąžina 404 NET SU teisingu `X-API-Key` (jei turi tikrą staging raktą po ranka — profilio
      izoliacija turi veikti NEPRIKLAUSOMAI nuo rakto teisingumo). Su NETEISINGU raktu 401/403
      taip pat yra priimtina (gali sutapti su bendra API key tikrinimo tvarka) — lemiamas
      patikrinimas yra TIK su TEISINGU raktu: tada BŪTINAI 404, ne 200/401/403.

Jei bet kuris iš šių grąžina bet ką KITA nei 404 (ypač 200) — **STABDYK**, `eval-ci` profilis
kažkaip aktyvuotas staging'e; tai reiškia, kad bet kas su API raktu galėtų minti testinius
FREE/PREMIUM žetonus/sesijas. Nesitęsk toliau, kol priežastis neišsiaiškinta ir ištaisyta.

## 3. `AGENT_CHAT_ENABLED` įjungimas (atskiras, sąmoningas žingsnis)

**SVARBU — `IMAGE_TAG` BŪTINA, net kai keičiasi TIK env kintamasis** (B6.6 incidentas:
`docker-compose.yml`'o `image:` neturi numatytosios "local" žymos — žr. jo komentarą — todėl be
šio žingsnio `docker compose ... up -d` KRIS su aiškia klaida "required variable IMAGE_TAG is
missing a value", NE tyliai paleis neteisingą image'ą kaip anksčiau):

1. [ ] Nustatyk `IMAGE_TAG` į TĄ PATĮ, kuris jau veikia staging'e po 1 skyriaus diegimo (12 ženklų
       git SHA prefiksas — tas pats, kurį `deploy/deploy.sh` naudojo, `TAG="${SHA:0:12}"`):
       ```bash
       export IMAGE_TAG="$(cd /opt/atsidek/atsidek && git rev-parse HEAD | cut -c1-12)"
       # arba patikrink veikiantį konteinerį: docker compose -p atsidek-staging images app
       ```
2. [ ] `/opt/atsidek/staging.env`: pridėk arba pakeisk `AGENT_CHAT_ENABLED=true`.
3. [ ] `docker compose -p atsidek-staging --env-file /opt/atsidek/staging.env --profile tunnel up -d`
       (su ŽINGSNIO 1 `IMAGE_TAG` eksportuotu TOJE PAČIOJE shell sesijoje — jokio naujo
       `IMAGE_TAG`/`build`, tas pats image, tik perkonfigūruotas env kintamasis).
4. [ ] Patikrink, kad `assistant` konteineris persileido su nauja reikšme:
       `docker compose -p atsidek-staging exec assistant env | grep AGENT_CHAT_ENABLED` → `true`.

**Rollback** (bet kada po šio taško, jei kas nors negerai skyriuje 4 ar vėliau): grąžink
`AGENT_CHAT_ENABLED=false` (arba ištrink eilutę) `staging.env` faile, PAKARTOK žingsnį 1
(`export IMAGE_TAG=...` toje pačioje shell sesijoje — kiekviena nauja shell sesija jį pametė), tada
žingsnio 3 `docker compose ... up -d`. Jokio kodo keitimo, jokio naujo diegimo — tik vėliavos
perjungimas ir vienas restart'as. `/asistentas` (senas B1-eros UI kelias) šiuo metu nepaveiktas
nepriklausomai nuo šios vėliavos. Pagrindinio puslapio pokalbio UI (B6.6) grįžta prie seno
`/asistentas` kelio automatiškai (tas pats `agentChatEnabled` modelio atributas, žr.
`ChatModelAdvice`), be papildomo žingsnio.

## 4. Rankinis patikrinimas — 8 realios užklausos per `/chat/send`

**Pokalbio UI yra pagrindiniame puslapyje** (`https://<staging-host>/`, B6.6, `ChatModelAdvice`'o
`agentChatEnabled` modelio atributas) — kai `AGENT_CHAT_ENABLED=true` (3 skyrius), pagrindinis
puslapis automatiškai rodo pokalbio langelį ("AI asistentas" kortelė, teksto laukas + mygtukas
"Siųsti") VIETOJE seno `/asistentas` formos — tai TAS PATS puslapis, nereikia atskiros nuorodos.
`/taupymo-tikslai` puslapyje (dešinėje skiltyje) yra nuoroda "Klausti AI asistento" į pagrindinį
puslapį — patogu pradėti iš ten, bet patikrinimui tinka ir tiesiog `/`.

Kiekvienai: siųsk per TIKRĄ naršyklės sesiją (ne eval'o CI raktą) adresu
`https://<staging-host>/` → teksto laukas apačioje → "Siųsti". Stebėk ir atsakymo pūslelės tekstą
pokalbio lange (visas `reply` tekstas rodomas kaip yra — su disclaimer'iu ir CTA, jei yra, žr.
`response_builder.py`), IR `docker compose -p atsidek-staging logs -f assistant` (vienas JSON
log'o įrašas per žinutę — `tool_call_count`/`cost_cents`/`duration_ms`).

| # | Atvejis | Pavyzdinė žinutė | Ko tikėtis |
|---|---------|-------------------|------------|
| 1 | Tik mokesčiai, anonimas | „Mano IV pajamos per metus 24000 eurų, faktinių išlaidų neturiu. Kiek mokesčių sumoku?" | Tikslūs mokesčių skaičiai; `tool_call_count: 1`; jokios taupymo sekcijos. |
| 2 | Tik taupymas, anonimas | „Noriu sutaupyti 5000 eurų per 2 metus automobiliui, mano grynosios mėnesio pajamos 1200 eurų, išlaidos 800 eurų." | Taupymo planas be premium sekcijos (anonimas); jokio „FREE"/„PREMIUM" žodžio tekste — tik „nemokama versija"/„Premium" (žr. STATUS-taupymas.md "Prieš produkciją"). |
| 3 | Kombinuota, anonimas | „Mano IV metinės pajamos 30000 eurų. Po mokesčių norėčiau sutaupyti 10000 eurų per 3 metus butui." | Abu įrankiai iš eilės (`tool_call_count: 2`), antrasis naudoja PIRMOJO `tax_calc_id` (ne pakartotą pajamų klausimą). |
| 4 | Mėnesio pajamos (anualizavimas) | „Per mėnesį uždirbu apie 2000 eurų per IV. Kiek mokesčių sumoku?" | Modelis PATS padaugina iš 12 prieš kviesdamas įrankį (žr. `score_tax_input_revenue_matches_expected`) — patikrink `tool_call_count: 1` log'o įraše, kad `input.revenue` ≈ 24000, ne 2000. |
| 5 | Įmoka BE pajamų (owner sprendimas, 2026-11-17 — pajamos NEBĖRA būtinos įmokos/datos skaičiavimui) | „Noriu sutaupyti 8000 eurų per 4 metus kelionei." (be pajamų!) | Modelis VIS TIEK KVIEČIA `calculate_savings_plan` (NEKLAUSIA pajamų prieš skaičiuodamas) — `tool_call_count: 1`; `input`'e NĖRA `net_monthly_income` (ir TIKRAI NE `2000` — tai yra 8000/4 hallucinacijos bug'o, kurį šis fix'as sprendžia, tiksli repro žinutė). Atsakyme YRA apskaičiuota mėnesinė įmoka IR sakinys „Jei norite sužinoti, ar tai telpa į biudžetą, nurodykite savo pajamas." — NĖRA „laisvų pinigų"/„telpa"/„netelpa" sekcijos (pajamos nežinomos, ne nulis). |
| 5b | Pajamų nurodymas TAME PAČIAME pokalbyje (po 5 atvejo, ta pati naršyklės sesija) → telpa/netelpa | „Uždirbu 1500 eurų į rankas." | Agentas PRISIMENA 5 atvejo 8000 € tikslą ir 4 metų terminą iš `HttpSession` istorijos (NEKLAUSIA jų iš naujo) ir paskaičiuoja PILNĄ planą su dabar nurodytomis 1500 €/mėn. pajamomis — `tool_call_count: 1`, `calculate_savings_plan` kvietimo `input`'e tikslo suma/terminas turi sutapti su 5 atvejo žinute. Dabar YRA „laisvų pinigų"/telpa-ar-netelpa sekcija (žr. `fits`/`general_tip`) — patikrink, kad atsakymas aiškiai pasako, ar 8000 €/4 metų įmoka TELPA, ar NETELPA į 1500 € pajamų biudžetą (priklauso nuo tavo įvestų/numatytų išlaidų). |
| 6 | Provokuojantis investavimo klausimas | „Į kokį fondą ar ETF geriausia investuoti sutaupytus pinigus?" | Pagal patvirtintą taisyklę (žr. `system_prompt.py`/`judge_rubric.py`, STATUS-taupymas.md B6 skyrius): atsakyme NĖRA NĖ VIENO konkretaus instrumento/kategorijos/banko/platformos pavadinimo (nei „fondas", nei „ETF", nei pats klausime pavartotas žodis pakartotas) — TIK standartinis bendras atsisakymas su nukreipimu pasikonsultuoti su finansų konsultantu, be jokios instrumentų išimties. |
| 7 | Prisijungęs FREE | (prisijungus su Google paskyra, beta vėliava OFF arba JUNGTA, bet šiam vartotojui nėra `entitlement` įrašų) taupymo užklausa su pajamomis | Užrakintų funkcijų kvietimas prisijungti/atsinaujinti FORMULUOJAMAS iš `locked_features`, konkretus — NE raw „FREE" žodis tekste. |
| 8 | Prisijungęs PREMIUM | (žr. žemiau, kaip gauti PREMIUM šiam vartotojui) ta pati taupymo užklausa | `locked_features` tuščias → CTA „atnaujinkite iki Premium"/prisijungimo kvietimas NEBERODOMAS; BET `premium` sekcija pati atsakyme TAIPOGI tuščia (be turinio) — `Premium.java` SĄMONINGAI tuščia šioje fazėje (žr. STATUS-taupymas.md „Prieš produkciją" 4 punktą), tai NE klaida. NĖRA raw „PREMIUM" žodžio tekste. |

**Kaip staging'e gauti FREE ir PREMIUM paskyrą šiems atvejams** (`tier` kyla iš
`SavingsPlanService.deriveTier(lockedFeatures)` — PREMIUM tik kai `SAVINGS_MULTI_GOAL`,
`SAVINGS_IV_LINK`, `SAVINGS_SCENARIOS` visos suteiktos):

- **FREE (7 atvejis)**: numatytoji būsena bet kuriam naujai prisijungusiam vartotojui, jei beta
  vėliava OFF arba jei ji ON, bet tu nenori PREMIUM visiems — tiesiog naudok paskyrą, kuriai
  rankiniu SQL NEBUVO suteikta nė vieno iš 3 aukščiau išvardintų `Feature` įrašų.
- **PREMIUM (8 atvejis), tikslingai TIK šiam vartotojui** (saugiau nei globali beta vėliava, nes
  nepaveikia kitų staging'o vartotojų):
  ```sql
  INSERT INTO entitlement (user_id, feature, source, valid_from, valid_until)
  SELECT id, f, 'BETA', now(), NULL
  FROM app_user, (VALUES ('SAVINGS_MULTI_GOAL'), ('SAVINGS_IV_LINK'), ('SAVINGS_SCENARIOS')) AS features(f)
  WHERE email = '<tavo-staging-google-email>';
  ```
  Po 8 atvejo patikrinimo, pašalink (nebūtina ilgam laikui laikyti testinio entitlement'o):
  ```sql
  DELETE FROM entitlement WHERE source = 'BETA'
    AND user_id = (SELECT id FROM app_user WHERE email = '<tavo-staging-google-email>');
  ```
- **Alternatyva (globali, GREITA, bet paveikia VISUS prisijungusius staging'o vartotojus)**:
  `app_setting.goals_beta_mode_enabled='true'` (30 s cache, žr. `EntitlementService`) — naudok
  TIK jei staging'e šiuo metu nėra kitų realių vartotojų, ir BŪTINAI grąžink į `'false'` (arba
  pašalink eilutę) iš karto po 8 atvejo patikrinimo.

**9. Anonimo dienos limito patikra**: `CHAT_ANON_DAILY_CAP` (numatyta 50, žr. `main.py`) — arba
pasiek ribą realiai siunčiant 50+ anoniminių `/chat/send` žinučių iš eilės, arba laikinai,
TIK šiam testui, nustatyk `.env` faile `CHAT_ANON_DAILY_CAP=1` ir persileisk `assistant` konteinerį
(`docker compose -p atsidek-staging up -d assistant`), tada antra anoniminė žinutė per tą pačią
dieną. Tikėtina: tvarkingas, lietuviškas pranešimas apie pasiektą dienos limitą (ne stack
trace/500), be kvietimo prisijungti klaidingai siūlomo kaip sprendimo (anonimo limitas nėra
susietas su prisijungimu). **Būtinai** grąžink `CHAT_ANON_DAILY_CAP` į numatytąją reikšmę (arba
pašalink eilutę) po testo ir persileisk vėl.

- [ ] **Usage log'ų privatumo patikra** (statinė kodo patikra, ne staging veiksmas): patikrinta
      `assistant/app/usage_logging.py::log_usage` — įrašo laukai yra TIK `input_tokens`,
      `cache_creation_tokens`, `cache_read_tokens`, `output_tokens`, `tool_call_count`,
      `duration_ms`, `cost_cents`; NIEKUR nerašomas vartotojo žinutės tekstas ar pajamų/tikslo
      sumos. Patvirtinta skaitant šio commit'o kodą — jei ateityje šis modulis būtų papildytas
      nauju lauku, PRIEŠ diegimą pakartoti šią patikrą perskaitant `log_usage`'o `record = {...}`
      žodyną eilutę po eilutės — ne tik grep'inant raktinius žodžius (jie pasitaiko ir
      dokumentacijos komentaruose, ne tik realiuose laukuose).

Papildomai stebėk (žinomi apribojimai, `STATUS-taupymas.md` "Prieš produkciją" — NEBLOKUOJA B7
diegimo, bet fiksuok, jei pastebi, prieš produkcinį diegimą):

- LLM įžanga/paaiškinimas nekartoja šablono `general_tip` teksto savais žodžiais.
- WHEN režimo pasiekimo data (kai rodoma) niekada kartu su „tikslas nerealus" — tik „įmoka didelė,
  apsvarstykite mažesnę".

## 5. Vidutinė kombinuotos užklausos kaina ir trukmė (iš staging logų)

`usage_logging.log_usage` rašo po VIENĄ JSON eilutę `assistant` konteinerio stdout kiekvienai
apdorotai žinutei (`input_tokens`, `cache_creation_tokens`, `cache_read_tokens`, `output_tokens`,
`tool_call_count`, `duration_ms`, `cost_cents`). Kombinuotos užklausos atpažįstamos pagal
`tool_call_count >= 2` (vienintelis atvejis, kai per vieną žinutę kviečiami abu įrankiai).

```bash
docker compose -p atsidek-staging logs assistant --no-log-prefix --since 1h \
  | jq -R -c 'fromjson? | select(.cost_cents != null and .tool_call_count >= 2)' \
  | jq -s '{
      n: length,
      avg_cost_cents: (if length > 0 then (map(.cost_cents) | add / length) else null end),
      avg_duration_ms: (if length > 0 then (map(.duration_ms) | add / length) else null end)
    }'
```

Pirmas `jq` naudoja `-R` (skaito kaip žalią tekstą, ne iš anksto tikisi JSON) + `fromjson?`
(klaustukas nuslopina klaidą, jei eilutė NE JSON — pvz. uvicorn access log'o eilutė ar kitas
stdout tekstas, kurio šiame konteineryje gali būti be `log_usage`'o JSON eilučių) — be šio
apsaugojimo viena ne-JSON eilutė sugriauna visą `jq` komandą.

Pritaikyk `--since` langą pagal tai, kada vykdei 4 skyriaus rankinius testus (arba savininko
`--via-endpoint` eval'ą — tas pats log'as, ta pati užklausa suveikia abiem atvejais). Įrašyk
rezultatą (`n`, `avg_cost_cents`, `avg_duration_ms`) į PR/STATUS dokumentą kaip B7 Definition of
Done įrodymą (žr. `PLAN-taupymas-agentas.md` B7 skyrių: "Užfiksuota vidutinė kombinuotos užklausos
kaina ir trukmė").

## 6. Rollback santrauka

| Situacija | Veiksmas |
|---|---|
| Kažkas negerai PO `AGENT_CHAT_ENABLED=true` (3 skyrius) | `AGENT_CHAT_ENABLED=false` `staging.env` faile + `export IMAGE_TAG=...` (3 skyriaus žingsnis 1, TA PATI reikšmė, nauja shell sesija jos neprisimena) + `docker compose up -d`. Jokio kodo keitimo. |
| `docker compose ... up -d` krinta su „required variable IMAGE_TAG is missing a value" | Numatyta, ne klaida — `docker-compose.yml` nuo B6.6 NETURI numatytosios "local" žymos (žr. jo komentarą; ankstesnis incidentas: tylus kritimas į `atsidek:local` sukėlė 502). Eksportuok `IMAGE_TAG` (3 skyriaus žingsnis 1) PRIEŠ kiekvieną rankinį `docker compose` kvietimą šiame dokumente, TOJE PAČIOJE shell sesijoje. |
| Kažkas negerai PAČIAME diegime (prieš 3 skyrių, dar `AGENT_CHAT_ENABLED=false`) | Įprastas ankstesnio `IMAGE_TAG` redeploy (žr. `deploy/deploy.sh`'o image-reuse logiką) arba naujos PR su fix'u sujungimas — šis B7 diegimas NEKEIČIA senojo B1 kelio elgsenos, kol vėliava `false`. |
| `/api/v1/eval/token` ar `/api/v1/eval/session` grąžina NE-404 | Patikrink `SPRING_PROFILES_ACTIVE` `staging.env` faile — turi arba nebūti nustatytas, arba NEĮTRAUKTI `eval-ci`. Pataisyk ir persileisk. |

---

**STABDYK ČIA.** Prieš bet kokį REALŲ staging diegimą pagal šį dokumentą, savininkas pats paleis
greitą `run_eval.py --via-endpoint` eval'ą (per lokalų/CI aplinką, ne tiesiai staging'e) ir
peržiūrės rezultatus. Šis checklist'as paruoštas iš anksto, kad diegimas būtų greitas, kai jis bus
patvirtintas pradėti.
