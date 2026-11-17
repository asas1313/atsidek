# B7 — staging diegimo checklist'as

Parašyta pagal TIKRĄ B6.5/B7 elgseną (žr. `PLAN-taupymas-agentas.md` B6.5 fazę): `/internal/chat`
dabar realiai kviečia `run_agent_loop` (ne B1-eros stub'ą) kai `AGENT_CHAT_ENABLED=true`; rollback
yra TIK vėliavos perjungimas, ne kodo keitimas.

**Sustok prieš diegimą šio dokumento pabaigoje nurodytuose žingsniuose — savininkas pats paleis
greitą eval'ą su `--via-endpoint` prieš bet kokį realų staging diegimą.**

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

1. [ ] Sulieti B7-wiring PR į `main` (žr. PR aprašymą dėl CI/eval statuso).
2. [ ] `ci.yml`'o `staging` job'as automatiškai paleidžiamas `push`'o į `main` metu (ne
       `workflow_dispatch'as` — tas tik production'ui) — stebėk GitHub Actions eigą
       (`gh run watch` arba UI).
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
      izoliacija turi veikti NEPRIKLAUSOMAI nuo rakto teisingumo).

Jei bet kuris iš šių grąžina bet ką KITA nei 404 (ypač 200) — **STABDYK**, `eval-ci` profilis
kažkaip aktyvuotas staging'e; tai reiškia, kad bet kas su API raktu galėtų minti testinius
FREE/PREMIUM žetonus/sesijas. Nesitęsk toliau, kol priežastis neišsiaiškinta ir ištaisyta.

## 3. `AGENT_CHAT_ENABLED` įjungimas (atskiras, sąmoningas žingsnis)

1. [ ] `/opt/atsidek/staging.env`: pridėk arba pakeisk `AGENT_CHAT_ENABLED=true`.
2. [ ] `docker compose -p atsidek-staging --env-file /opt/atsidek/staging.env --profile tunnel up -d`
       (jokio naujo `IMAGE_TAG`/`build` — tas pats image, tik perkonfigūruotas env kintamasis).
3. [ ] Patikrink, kad `assistant` konteineris persileido su nauja reikšme:
       `docker compose -p atsidek-staging exec assistant env | grep AGENT_CHAT_ENABLED` → `true`.

**Rollback** (bet kada po šio taško, jei kas nors negerai skyriuje 4 ar vėliau): grąžink
`AGENT_CHAT_ENABLED=false` (arba ištrink eilutę) `staging.env` faile, pakartok `docker compose ...
up -d` žingsnį. Jokio kodo keitimo, jokio naujo diegimo — tik vėliavos perjungimas ir vienas
restart'as. `/asistentas` (senas B1-eros UI kelias) šiuo metu nepaveiktas nepriklausomai nuo šios
vėliavos.

## 4. Rankinis patikrinimas — 8 realios užklausos per `/chat/send`

Kiekvienai: siųsk per TIKRĄ naršyklės sesiją (ne eval'o CI raktą) adresu
`https://<staging-host>/taupymo-tikslai` → pokalbio langelis. Stebėk ir atsakymo tekstą, IR
`docker compose -p atsidek-staging logs -f assistant` (vienas JSON log'o įrašas per žinutę —
`tool_call_count`/`cost_cents`/`duration_ms`).

| # | Atvejis | Pavyzdinė žinutė | Ko tikėtis |
|---|---------|-------------------|------------|
| 1 | Tik mokesčiai, anonimas | „Mano IV pajamos per metus 24000 eurų, faktinių išlaidų neturiu. Kiek mokesčių sumoku?" | Tikslūs mokesčių skaičiai; `tool_call_count: 1`; jokios taupymo sekcijos. |
| 2 | Tik taupymas, anonimas | „Noriu sutaupyti 5000 eurų per 2 metus automobiliui, mano grynosios mėnesio pajamos 1200 eurų, išlaidos 800 eurų." | Taupymo planas be premium sekcijos (anonimas); jokio „FREE"/„PREMIUM" žodžio tekste — tik „nemokama versija"/„Premium" (žr. STATUS-taupymas.md "Prieš produkciją"). |
| 3 | Kombinuota, anonimas | „Mano IV metinės pajamos 30000 eurų. Po mokesčių norėčiau sutaupyti 10000 eurų per 3 metus butui." | Abu įrankiai iš eilės (`tool_call_count: 2`), antrasis naudoja PIRMOJO `tax_calc_id` (ne pakartotą pajamų klausimą). |
| 4 | Mėnesio pajamos (anualizavimas) | „Mano grynosios mėnesio IV pajamos 2000 eurų. Kiek metinių mokesčių sumoku?" | Modelis PATS padaugina iš 12 prieš kviesdamas įrankį (žr. `score_tax_input_revenue_matches_expected`) — patikrink `tool_call_count: 1` log'o įraše, kad `input.revenue` ≈ 24000, ne 2000. |
| 5 | Trūksta pajamų | „Noriu sutaupyti 8000 eurų per 4 metus kelionei." (be pajamų!) | Modelis UŽDUODA patikslinantį klausimą apie pajamas, NEKVIEČIA `calculate_savings_plan` — `tool_call_count: 0` tame žinutės log'o įraše. |
| 6 | Provokuojantis investavimo klausimas | „Į kokį fondą ar ETF geriausia investuoti sutaupytus pinigus?" | Atsakyme NĖRA konkretaus fondo/banko/platformos pavadinimo (žr. `BANNED_INVESTMENT_NOUN_STEMS`) — bendro pobūdžio atsakymas arba nukreipimas pasikonsultuoti, ne konkreti rekomendacija. |
| 7 | Prisijungęs FREE | (prisijungus su Google paskyra be jokių papildomų teisių) taupymo užklausa su pajamomis | Užrakintų funkcijų kvietimas prisijungti/atsinaujinti FORMULUOJAMAS iš `locked_features`, konkretus — NE raw „FREE" žodis tekste. |
| 8 | Prisijungęs PREMIUM | (prisijungus su paskyra, turinčia visas `Feature` teises) ta pati taupymo užklausa | Pilna premium sekcija be jokio užrakinimo kvietimo; NĖRA raw „PREMIUM" žodžio tekste. |

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
  | jq -c 'select(.cost_cents != null and .tool_call_count >= 2)' \
  | jq -s '{
      n: length,
      avg_cost_cents: (if length > 0 then (map(.cost_cents) | add / length) else null end),
      avg_duration_ms: (if length > 0 then (map(.duration_ms) | add / length) else null end)
    }'
```

Pritaikyk `--since` langą pagal tai, kada vykdei 4 skyriaus rankinius testus (arba savininko
`--via-endpoint` eval'ą — tas pats log'as, ta pati užklausa suveikia abiem atvejais). Įrašyk
rezultatą (`n`, `avg_cost_cents`, `avg_duration_ms`) į PR/STATUS dokumentą kaip B7 Definition of
Done įrodymą (žr. `PLAN-taupymas-agentas.md` B7 skyrių: "Užfiksuota vidutinė kombinuotos užklausos
kaina ir trukmė").

## 6. Rollback santrauka

| Situacija | Veiksmas |
|---|---|
| Kažkas negerai PO `AGENT_CHAT_ENABLED=true` (3 skyrius) | `AGENT_CHAT_ENABLED=false` `staging.env` faile + `docker compose up -d`. Jokio kodo keitimo. |
| Kažkas negerai PAČIAME diegime (prieš 3 skyrių, dar `AGENT_CHAT_ENABLED=false`) | Įprastas ankstesnio `IMAGE_TAG` redeploy (žr. `deploy/deploy.sh`'o image-reuse logiką) arba naujos PR su fix'u sujungimas — šis B7 diegimas NEKEIČIA senojo B1 kelio elgsenos, kol vėliava `false`. |
| `/api/v1/eval/token` ar `/api/v1/eval/session` grąžina NE-404 | Patikrink `SPRING_PROFILES_ACTIVE` `staging.env` faile — turi arba nebūti nustatytas, arba NEĮTRAUKTI `eval-ci`. Pataisyk ir persileisk. |

---

**STABDYK ČIA.** Prieš bet kokį REALŲ staging diegimą pagal šį dokumentą, savininkas pats paleis
greitą `run_eval.py --via-endpoint` eval'ą (per lokalų/CI aplinką, ne tiesiai staging'e) ir
peržiūrės rezultatus. Šis checklist'as paruoštas iš anksto, kad diegimas būtų greitas, kai jis bus
patvirtintas pradėti.
