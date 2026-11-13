# B2 pairing užrašai: `calculate_savings_plan` įrankis

> Superseded 2026-11-12 — see Owner decision 13 in PLAN-taupymas-agentas.B.plan.md; B2 was
> implemented autonomously, not as a pairing session. Kept below for its still-accurate technical
> rationale.

Šaka: `feat/taupymas-agentas-b2`. Agentas paruošė schemą ir "raudonus" testus (žr. žemiau);
**handler'io kūną ir sprendimo (resolution) logiką rašo savininkas**, agentui peržiūrint diff'us
ir paaiškinant Java pusės pasekmes. Tai yra tyčia — plano "Collaborative phases" skyrius šią fazę
įvardija kaip vieną iš trijų (B2/B3/B6), kurias reikia rašyti kartu, ne pilnai delegoti.

## Ką agentas paruošė

- `assistant/app/savings_tool.py` — `CalculateSavingsPlanInput` (AC-B2-1), pilnai veikiantis:
  atspindi `SavingsPlanRequest` (Java) lauką po lauko, plius du tik-įrankio laukai
  (`tax_calc_id`, `net_monthly_income`), kurie tarpusavyje išskiriantys (abu vienu metu → 400).
  `make_calculate_savings_plan_tool(...)` — tyčia `NotImplementedError`, tai jūsų rašytina dalis.
- `assistant/tests/test_savings_tool.py` — AC-B2-1 testai žali (schema pati savaime veikia);
  AC-B2-2/3/4 testai raudoni (žlunga su `NotImplementedError`, kol handler'is nerašytas) — kiekvienas
  testas yra konkretus tikslas, ką handler'is turi padaryti.
- `assistant/app/request_context.py` — pridėtas `UnknownResultError` (mirroring `ToolRegistry`'o
  `UnknownToolError`), kad `get_result()` nebegrąžintų plikos `KeyError` (B0 paliktas TODO,
  tiesiogiai reikalingas AC-B2-2's "nežinomas id → įrankio klaida" testui).

## Kodėl `net_monthly_income` yra "trickiest part"

`SavingsPlanApiController`/`SavingsPlanRequest` (A3, jau production'e) **reikalauja**
`net_monthly_income` lauko kūne — be jo Java grąžina 400. Java pati šito lauko niekada neskaičiuoja
ir niekada neims iš statistikos (PLAN.md principas: pajamos niekada neimamos iš statistikos be
patikslinančio klausimo).

Tai reiškia: **handler'is, ne Java**, turi prieš kiekvieną `/api/v1/savings/plan` kvietimą turėti
konkrečią `net_monthly_income` reikšmę rankoje. Yra trys galimi keliai (AC-B2-2/3):

1. **Iš ankstesnio `calculate_iv_taxes` kvietimo** (B1) — modelis atsiunčia `tax_calc_id`, kurį
   B1's `calculate_iv_taxes` jau sugrąžino ir į `RequestContext` įrašė per `store_result()`.
   Handler'is skaito `ctx.get_result(tax_calc_id)` ir iš saugomo žalio Java atsakymo ištraukia
   `["better"]["netMonthlyIncome"]` (ir `["better"]["monthlyReserve"]` → `monthly_tax_setaside`).
   Nežinomas `tax_calc_id` → `UnknownResultError` (jau paruošta infrastruktūra, žr. aukščiau),
   kurį handler'is turi arba perduoti toliau (B3's `is_error` tool_result kontraktas), arba
   susigaudyti ir paversti aiškia įrankio klaida — tai jūsų sprendimas, kaip tiksliai tą klaidą
   suformuluoti modeliui.
2. **Tiesiai iš vartotojo žodžių** — modelis pats pateikia `net_monthly_income` (pvz. vartotojas
   pasakė "gaunu 1500 į rankas"). Tada naudojama tiesiai, be jokio Java kvietimo į `/compare`.
3. **Nei vieno, nei kito** — handler'is **NIEKADA** neturi spėti/imti iš statistikos. Tai turi
   būti aiški įrankio klaida ("reikia pajamų"), kurią B3's agentų ciklas/B6's eval'ai interpretuos
   kaip signalą paklausti vartotojo, ne kviesti Java su išgalvotu skaičiumi. Tikslus klaidos tipas
   (naujas `Exception` paveldas, ar bendras `ToolError`?) — jūsų sprendimas; testas
   (`test_neither_tax_calc_id_nor_net_monthly_income_is_a_tool_error_not_a_guess`) tik tikrina, kad
   KAŽKOKIA klaida metama IR kad Java (per `FakeSavingsApi`) niekada nebūna kviečiama.

## Tapatybės/žetono perdavimas (AC-B2-4) — Java pusė JAU VISIŠKAI PARUOŠTA

Skirtingai nei `net_monthly_income`, šito dalykui **NEREIKIA** jokio naujo Java kodo — B1 jau
pridėjo tiksliai šitą mechanizmą `SavingsPlanApiController`'yje:

- `X-Atsidek-Context-Token` antraštė nebūtina; jos nebuvimas → šiandieninė (anoniminė/sesijos)
  elgsena nepakitusi.
- Esant jai IR galiojančiai → žetono `userId` nustato tapatybę (net jei nėra Spring sesijos — tai
  ir yra visa ADR-0003 esmė: Python kvietimas niekada neturi naršyklės sesijos).
- `tier` iš žetono **IGNORUOJAMAS** — `SavingsPlanService` visada iš naujo tikrina teises per
  `EntitlementService`. Handler'iui tai reiškia: tiesiog perduokite žetoną nepakeistą (tiksliai
  taip, kaip B1's `calculate_iv_taxes` daro per `calculator.compare(..., context_token)`), ir
  nesijaudinkite dėl `tier'o` – Java pati jį teisingai nustatys.
- Anoniminiam skambinimui `SavingsPlanResponse` grąžina atsakymą BE `premium` rakto (ne `null` —
  rakto visiškai nėra JSON'e, žr. `@JsonInclude(NON_NULL)`). Handler'io grąžinamas modeliui
  payload'as turi tą pačią savybę išlaikyti (žr. raudoną testą
  `test_anonymous_caller_never_sees_a_premium_key_in_the_tool_result`).

## Ko trūksta Python pusėje (jūsų rašytina)

`assistant/app/calculator.py`'s `AtsidekApi` turi `compare()`, bet **neturi** metodo, kviečiančio
`/api/v1/savings/plan` — Python iki šiol šito endpoint'o niekada nekvietė. Reikės naujo metodo
(pvz. `AtsidekApi.savings_plan(goal, net_monthly_income, monthly_tax_setaside, monthly_expenses,
household, assumptions, context_token=None)`), kuris yra tiesiog `compare()`'o šablono atkartojimas
kitam endpoint'ui (tas pats `X-API-Key`/`X-Atsidek-Context-Token` antraščių elgesys, tas pats JSON
serializavimas per `_json_default`, tas pats `CalculatorError(status_code=...)` naudojimas 400 vs.
kitiems ne-200 atsakymams). Tai yra mechaninė dalis — agentas gali tai parašyti kartu su jumis per
peržiūrą, kai pasieksite šitą žingsnį, arba jūs patys, žiūrėdami į `compare()` kaip šabloną.

## AC-B2 santrauka → testai

| AC | Testas | Būklė |
|----|--------|-------|
| AC-B2-1 | `test_rejects_payload_with_both_tax_calc_id_and_net_monthly_income`, `test_accepts_tax_calc_id_alone`, `test_accepts_net_monthly_income_alone`, `test_accepts_neither_tax_calc_id_nor_net_monthly_income_at_schema_level` | ✅ žali |
| AC-B2-2 | `test_known_tax_calc_id_injects_the_exact_b1_stored_values`, `test_unknown_tax_calc_id_is_a_tool_error_not_a_crashed_turn` | 🔴 raudoni |
| AC-B2-3 | `test_user_stated_net_monthly_income_is_used_directly`, `test_neither_tax_calc_id_nor_net_monthly_income_is_a_tool_error_not_a_guess` | 🔴 raudoni |
| AC-B2-4 | `test_context_token_is_forwarded_unmodified_same_as_b1`, `test_anonymous_caller_never_sees_a_premium_key_in_the_tool_result` | 🔴 raudoni |

Visi 🔴 testai šiuo metu žlunga su `NotImplementedError` (tyčia — žr. `savings_tool.py`), ne su
klaidingu assert'u. Kai pradėsime rašyti `make_calculate_savings_plan_tool`'io kūną, jie taps
realiais TDD red→green žingsniais.
