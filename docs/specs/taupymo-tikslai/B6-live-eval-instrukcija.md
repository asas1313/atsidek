# B6: kaip paleisti gyvą agento eval'ą (`workflow_dispatch`)

Trumpa instrukcija savininkui (ne agentui) - kas vieną kartą nustatoma GitHub'e ir kaip paleisti
`.github/workflows/eval-b6.yml`. Workflow pats pakelia tikrą Spring Boot + PostgreSQL (service
container) runner'yje - jums nereikia nieko paleisti lokaliai ar serveryje.

## 0. Būtina tvarka: workflow failas pirma turi būti `main`'e

`workflow_dispatch` GitHub'e matomas/paleidžiamas TIK jei workflow failas egzistuoja
**numatytojoje (`main`) šakoje** - net jei norite jį paleisti prieš KITOS šakos kodą. Dėl to:

- Workflow failas (`.github/workflows/eval-b6.yml`) pateiktas ATSKIRA, maža PR į `main`
  (**PR #48**, tik šis vienas failas, be likusio B6 kodo) - sujunkite PIRMA ją.
- Likęs B6 kodas (eval harness, `scoring.py`, `judge_rubric.py`, naujas Java
  `eu.atsidek.evalci` paketas, kurį workflow kviečia) lieka **PR #47**
  (`feat/taupymas-agentas-b6`), kol jis pats bus apžvelgtas/sujungtas - workflow JAME TURI
  vykdyti bet kokiu atveju, žr. žingsnį 3 žemiau.

## 1. Vienkartinis nustatymas GitHub'e

Reikalingas TIK vienas repo secret (workflow pats generuoja ir naudoja ekonominius, vienkartinius
`ATSIDEK_API_KEY`/`ATSIDEK_CHAT_CONTEXT_TOKEN_SECRET` kiekvienam paleidimui - nereikia jų nustatyti jums):

1. GitHub → repo → **Settings → Secrets and variables → Actions → New repository secret**.
2. Name: `ANTHROPIC_API_KEY`
3. Value: tikras Anthropic API raktas (tas pats, kuris leidžia kviesti `claude-haiku-5-5` ir
   `claude-sonnet-5-5`).
4. **Save**.

Tai viskas - PostgreSQL, Spring Boot aplikacija (su `SPRING_PROFILES_ACTIVE=eval-ci`, kad
`/api/v1/eval/token` logged_in FREE/PREMIUM atvejams egzistuotų) ir Python assistant servisas
startuoja patys workflow viduje, naudojant ephemeral (vienkartinius, tik šiam paleidimui)
duomenis/raktus.

## 2. Kaip paleisti

1. GitHub → repo → **Actions** → kairėje sąraše pasirinkite **"B6 agento eval (live, rankinis)"**.
2. **Run workflow** (dešinėje).
3. **SVARBU - šakos pasirinkimas**: lauke "Use workflow from" pasirinkite
   **`feat/taupymas-agentas-b6`**, NE `main` - `main` dar neturi paties eval kodo
   (`assistant/evals/agent/`, `eu.atsidek.evalci`), tik šį workflow failą (žr. žingsnį 0). Jei
   paleisite nuo `main`, žingsnis "Run live agent eval" tiesiog nerastų ko paleisti.
4. Du pasirenkami laukai:
   - `with_judge` - `true`, jei norite TAIP PAT kviesti DRAFT LLM-judge (`claude-sonnet-5-5`,
     papildoma kaina). Rubrika/slenkstis šiuo metu NEPATVIRTINTI (žr. `judge_rubric.py`) - pirmas
     gyvas paleidimas yra tam, kad turėtumėte ką tvirtinti. Palikite `false`, jei norite tik
     nemokamą deterministinį patikrinimą.
   - `model` - agento modelis, default `claude-haiku-5-5` (production reikšmė).
5. **Run workflow**.

## 3. Kur rasti rezultatus

- Veikimo eigą matysite tiesiai Actions UI (gyvas log).
- Po pabaigos, **Summary** puslapyje bus `b6-eval-results` artefaktas (JSON, 30 dienų) -
  parsisiųskite ir atidarykite - tai tas pats failas, kurį `run_eval.py` rašytų lokaliai į
  `assistant/evals/results/`.
- **SVARBU**: bendras `overall_pass_rate`/`group_pass_rates` dabar skaičiuojamas TIK iš
  neinvestavimo-patarimo matų (tool_sequence, no_invented_numbers, anon_no_premium,
  missing_income_handled, tax_input_revenue_matches_expected) - 0.9 slenkstis taikomas tik jiems.
- Investavimo patarimo patikrinimas (ir deterministinis, ir, jei įjungtas, judge) yra **0 FAIL
  slenkstis**: kiekvienas pažeidimas atsispindi atskirame JSON lauke
  `investment_advice_violations` (su pilnu tekstu) IR workflow log'e (stderr, raudonu įspėjimu),
  jei bent vienas pasitaiko - patikrinkite jį rankiniu būdu prieš tvirtindami rubriką/slenkstį.
- Kiekvienas atskiras paleidimas (`case_results[*].runs[*]`) dabar neša `actual_tier_seen`
  (`"FREE"`/`"PREMIUM"`/`null`, jei tam atvejui taupymo plano įrankis net nebuvo kviečiamas) ir
  `had_premium_section` (`true`/`false`/`null`) - tikrasis Java atsakymas, ne tik tikėtasi. Jei
  `logged_in` atvejo tikrasis tier'as nesutampa su tikėtuoju, workflow'as PATS NUKRENTA (ne tik
  pažymi scoring klaidą) - žr. `run_eval.py`'s `_run_one`.

## 4. Kaina

Kiekvienas paleidimas: 42 atvejai × 3 kartojimai = 126 `claude-haiku-5-5` (ar pasirinkto modelio)
kvietimai, plius (jei `with_judge=true`) papildomai 126 `claude-sonnet-5-5` kvietimai judge'ui.

## 5. Žinomi incidentai

- **2026-11-13**: pirmas gyvas paleidimas krito - `POST /api/v1/eval/token` grąžino 500
  (`ContextTokenService` fail-closed), nes workflow'o env kintamojo pavadinimas buvo
  `ATSIDEK_CHAT_TOKEN_SECRET` (trūko "CONTEXT") - Spring'o relaxed env-var binding savybei
  `atsidek.chat.context-token-secret` tyliai NESUVEIKIA su šiuo pavadinimu, nepaisant to, kad
  pati reikšmė buvo generuojama teisingai (`openssl rand` + `add-mask`, ne hardcode'inta). Taisyta
  visur (`eval-b6.yml`, `docker-compose.yml`, `application.yml`, `ContextTokenService.java`, šis
  failas) - teisingas pavadinimas yra `ATSIDEK_CHAT_CONTEXT_TOKEN_SECRET`. Žr.
  `docs/specs/taupymo-tikslai/B6-log.log` (pilnas stacktrace) ir
  `src/test/java/eu/atsidek/auth/ContextTokenSecretEnvBindingTest.java` (regresinis testas, kuris
  būtų pagavęs šią klaidą be gyvo paleidimo).
