# B6: kaip paleisti gyvą agento eval'ą (`workflow_dispatch`)

Trumpa instrukcija savininkui (ne agentui) - kas vieną kartą nustatoma GitHub'e ir kaip paleisti
`.github/workflows/eval-b6.yml`. Workflow pats pakelia tikrą Spring Boot + PostgreSQL (service
container) runner'yje - jums nereikia nieko paleisti lokaliai ar serveryje.

## 1. Vienkartinis nustatymas GitHub'e

Reikalingas TIK vienas repo secret (workflow pats generuoja ir naudoja ekonominį, vienkartinį
`ATSIDEK_API_KEY` kiekvienam paleidimui - nereikia jo nustatyti jums):

1. GitHub → repo → **Settings → Secrets and variables → Actions → New repository secret**.
2. Name: `ANTHROPIC_API_KEY`
3. Value: tikras Anthropic API raktas (tas pats, kuris leidžia kviesti `claude-haiku-5-5` ir
   `claude-sonnet-5-5`).
4. **Save**.

Tai viskas - PostgreSQL, Spring Boot aplikacija ir Python assistant servisas startuoja patys
workflow viduje, naudojant ephemeral (vienkartinius, tik šiam paleidimui) duomenis/raktus.

## 2. Kaip paleisti

1. GitHub → repo → **Actions** → kairėje sąraše pasirinkite **"B6 agento eval (live, rankinis)"**.
2. **Run workflow** (dešinėje).
3. Pasirinkite šaką (pvz. šią B6 PR šaką - workflow veikia bet kurioje šakoje, ne tik `main`).
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

## 4. Kaina

Kiekvienas paleidimas: 42 atvejai × 3 kartojimai = 126 `claude-haiku-5-5` (ar pasirinkto modelio)
kvietimai, plius (jei `with_judge=true`) papildomai 126 `claude-sonnet-5-5` kvietimai judge'ui.
