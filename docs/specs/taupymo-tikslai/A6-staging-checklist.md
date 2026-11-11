# A6. Staging patikros sąrašas (`PLAN-taupymas-agentas.md` §A6)

> Šis sandbox'as neturi tinklo prieigos į `staging.atsidek.eu`, todėl A6 negalėjo būti patikrintas automatiškai.
> Žemiau — `curl` komandų sąrašas savininkui arba CI, paleidžiamas **po** to, kai PR #33–#37 (A1–A5)
> sulieti į `main` ir išdiegti į staging'ą. Pakeiskite `$HOST` į `https://staging.atsidek.eu`.

## 0. Prieš pradedant

```bash
export HOST=https://staging.atsidek.eu
curl -sS -o /dev/null -w "%{http_code}\n" "$HOST/"   # tikimasi 200
```

## 1. `POST /api/v1/savings/plan` — 4 režimai, anonimas

```bash
# YEARS
curl -sS -X POST "$HOST/api/v1/savings/plan" -H "Content-Type: application/json" -d '{
  "goal": {"type": "HOME_DOWN_PAYMENT", "amount": 15000, "mode": "YEARS", "years": 5},
  "net_monthly_income": 3100, "monthly_tax_setaside": 900
}' | python3 -m json.tool
# Tikimasi: 200, "tier":"FREE", BE "premium" rakto, "locked_features" turi 3 reikšmes
# (SAVINGS_MULTI_GOAL, SAVINGS_IV_LINK, SAVINGS_SCENARIOS)

# DATE
curl -sS -X POST "$HOST/api/v1/savings/plan" -H "Content-Type: application/json" -d '{
  "goal": {"type": "CAR", "amount": 8000, "mode": "DATE", "target_year": 2028, "target_month": 6},
  "net_monthly_income": 2500
}' | python3 -m json.tool

# CHILD_AGE
curl -sS -X POST "$HOST/api/v1/savings/plan" -H "Content-Type: application/json" -d '{
  "goal": {"type": "CHILD", "amount": 20000, "mode": "CHILD_AGE", "child_current_age": 4, "child_target_age": 18},
  "net_monthly_income": 2800, "household": {"adults": 2, "children": 1}
}' | python3 -m json.tool

# WHEN
curl -sS -X POST "$HOST/api/v1/savings/plan" -H "Content-Type: application/json" -d '{
  "goal": {"type": "OTHER", "amount": 1200, "mode": "WHEN", "monthly_contribution": 100},
  "net_monthly_income": 2000
}' | python3 -m json.tool
# Tikimasi: "fits"/"reachable" laukai, be klaidų
```

**Rankinis patikrinimas kiekvienam atsakymui:**
- [ ] `core.monthly_contribution`/`core.free_cash_monthly`/`core.share_of_free_cash_pct` skaičiai sutampa su ranka paskaičiuotais.
- [ ] `core.disclaimer` = "Skaičiavimai informaciniai, tai nėra finansinis ar investavimo patarimas."
- [ ] `locked_features` = lygiai `["SAVINGS_MULTI_GOAL","SAVINGS_IV_LINK","SAVINGS_SCENARIOS"]` (bet kokia tvarka).
- [ ] JSON atsakyme **nėra** `premium` rakto (anonimas).

## 2. Statistikos reikšmės su kilme (A2 + A3)

```bash
curl -sS -X POST "$HOST/api/v1/savings/plan" -H "Content-Type: application/json" -d '{
  "goal": {"type": "CHILD", "amount": 20000, "mode": "YEARS", "years": 10},
  "net_monthly_income": 2800
}' | python3 -m json.tool
```
- [ ] `monthly_expenses` praleistas → `core.assumptions` turi įrašą `field: "monthly_expenses"`, `origin: "STATISTICS"`, užpildytas `source`/`source_date` (arba aiškiai pažymėta iliustracinė reikšmė).
- [ ] `annual_return_pct`/`annual_inflation_pct` praleisti → `core.assumptions` turi `origin: "ASSUMPTION"` įrašus su numatytosiomis 0%/2.5% reikšmėmis (A1).

## 3. Klaidų atvejai (400/415, ne 500)

```bash
# trūksta goal
curl -sS -o /dev/null -w "%{http_code}\n" -X POST "$HOST/api/v1/savings/plan" -H "Content-Type: application/json" -d '{"net_monthly_income": 1000}'
# tikimasi 400

# trūksta net_monthly_income
curl -sS -o /dev/null -w "%{http_code}\n" -X POST "$HOST/api/v1/savings/plan" -H "Content-Type: application/json" -d '{"goal": {"type":"CAR","amount":1000,"mode":"YEARS","years":2}}'
# tikimasi 400

# nežinomas laukas
curl -sS -o /dev/null -w "%{http_code}\n" -X POST "$HOST/api/v1/savings/plan" -H "Content-Type: application/json" -d '{"goal": {"type":"CAR","amount":1000,"mode":"YEARS","years":2}, "unknown_field": 1}'
# tikimasi 400

# ne JSON Content-Type
curl -sS -o /dev/null -w "%{http_code}\n" -X POST "$HOST/api/v1/savings/plan" -H "Content-Type: text/plain" -d 'bet kas'
# tikimasi 415

# šlamštas po JSON
curl -sS -o /dev/null -w "%{http_code}\n" -X POST "$HOST/api/v1/savings/plan" -H "Content-Type: application/json" -d '{"goal": {"type":"CAR","amount":1000,"mode":"YEARS","years":2}} šlamštas'
# tikimasi 400

# per didelė suma
curl -sS -X POST "$HOST/api/v1/savings/plan" -H "Content-Type: application/json" -d '{"goal": {"type":"CAR","amount":999999999999,"mode":"YEARS","years":2}, "net_monthly_income": 1000}' | python3 -m json.tool
# tikimasi 400, priežastis su "goal.amount", NE su "amount"
```
- [ ] Visi grąžina 400/415, **ne** 500.
- [ ] Klaidos priežastyje (`reason`) minimas **viešas** užklausos laukas (pvz. `goal.amount`), ne vidinis (`amount`).

## 4. `GET /goals/savings/locked-features` (A5)

```bash
curl -sS "$HOST/goals/savings/locked-features" | python3 -m json.tool
# anonimui tikimasi: {"lockedFeatures": ["SAVINGS_MULTI_GOAL","SAVINGS_SCENARIOS","SAVINGS_IV_LINK"]} (tvarka gali skirtis)
```
- [ ] Anonimui — visos 3 funkcijos užrakintos.
- [ ] Prisijungus naršyklėje (su sesijos slapuku) — šis endpoint'as grąžina tuščią sąrašą (beta vėliava aktyvi).

## 5. Prisijungusio vartotojo patikra (reikia naršyklės, ne `curl`)

1. Atsidarykite `$HOST/taupymo-tikslai` naršyklėje, neprisijungę.
   - [ ] CTA kortelė rodoma su vienu iš 3 fiksuotų sakinių (ne statiniu tekstu iš praėjusio inkremento).
   - [ ] Nėra blur'intų ar netikrų skaičių jokioje sekcijoje.
2. Prisijunkite per Google.
   - [ ] CTA kortelė visai nerodoma (arba paslepiama, jei `locked_features` tuščias).
   - [ ] Pasikartokite 1 skyriaus `curl` užklausas su sesijos slapuku (naršyklės dev tools → kopijuoti `Cookie` antraštę) — atsakyme **yra** `"premium": {}` raktas.

## 6. `STATUS-taupymas.md` DoD

- [ ] Po šių patikrų atnaujinti `docs/specs/taupymo-tikslai/STATUS-taupymas.md`: A1–A5 eilutes pažymėti **„padaryta"** (ne „padaryta (peržiūroje)"), A6 eilutę pažymėti pagal realius staging rezultatus.
- [ ] Jei kuri patikra nepavyksta — **nelaikyti A dalies DoD įvykdytu**, grąžinti atitinkamą PR pataisymui (ne naujam PR, tą pačią šaką).
