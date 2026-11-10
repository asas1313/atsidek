"""Eval rinkinys AI ištraukimui (`POST /api/v1/goals/parse`, žr. docs/specs/taupymo-tikslai/spec.md 7 sk.).

FORMATAS (pasirinktas šios fazės agento - paprastas Python modulis, nes:
  - leidžia komentarus prie kiekvienos grupės (JSON/CSV to negali);
  - laukai lengvai atpažįstami IDE/pytest aplinkoje be papildomo parserio.

`REFERENCE_DATE` - FIKSUOTA "šiandien" data, naudojama kviečiant ekstraktorių (`extract(text,
today=REFERENCE_DATE)`), kad santykinio laiko laukai ("po 5 metų", "iki vasaros") duotų
REPRODUKUOJAMĄ (ne nuo paleidimo datos priklausantį) rezultatą tarp eval paleidimų.

`CASES` - sąrašas žodynų su laukais:
  - id: str - unikalus atvejo identifikatorius.
  - text: str - laisvo teksto žinutė (vartotojo įvestis).
  - expected: dict - TIK tie `/api/v1/goals/parse` laukai, kuriuos norima vertinti šiam atvejui
    (goalType, mode, amount, years, targetYear, targetMonth, childCurrentAge, childTargetAge,
    monthlyContribution, members, workers, income, expenses). Laukas, kurio NĖRA šiame žodyne,
    tiesiog NEVERTINAMAS šiam atvejui (pvz. kai reikšmė tikrai neaiški/nesvarbu testui).
  - expect_missing_includes: list[str] (nebūtina) - kuriuos laukus TURI atpažinti kaip trūkstamus
    (`missingCritical`) - naudojama neaiškiems/nepilniems atvejams.
  - notes: str (nebūtina) - kodėl atvejis toks, ypač MVP "vienas tikslas vienu metu" dviprasmybėms
    (keli vaikai paminėti viename tekste - žr. 53-57 grupę žemiau ir PR aprašą).

Scoring: žr. run_eval.py šiame kataloge (tikslumas per lauką + bendras, paleidžiama RANKINIU būdu
su tikru ANTHROPIC_API_KEY, NE automatiškai per pytest).
"""
from datetime import date

REFERENCE_DATE = date(2026, 11, 10)

CASES = [
    # --- 1-10: YEARS režimas, aiškios formuluotės ---------------------------------------------
    {"id": "years_01", "text": "Noriu sutaupyti 20000 eurų per 5 metus automobiliui.",
     "expected": {"goalType": "CAR", "mode": "YEARS", "amount": 20000, "years": 5}},
    {"id": "years_02", "text": "Planuoju atsidėti 15 tūkst. eurų namo pradiniam įnašui per 8 metus.",
     "expected": {"goalType": "HOME_DOWN_PAYMENT", "mode": "YEARS", "amount": 15000, "years": 8}},
    {"id": "years_03", "text": "Sutaupyti 50000 € per 10 metų - tikslas kitas, dar neapsisprendžiau koks.",
     "expected": {"goalType": "OTHER", "mode": "YEARS", "amount": 50000, "years": 10}},
    {"id": "years_04", "text": "Noriu per 3 metus susitaupyti 9000 eurų automobiliui.",
     "expected": {"goalType": "CAR", "mode": "YEARS", "amount": 9000, "years": 3}},
    {"id": "years_05", "text": "Taupau vaikui - per 12 metų reikės 25000 eurų.",
     "expected": {"goalType": "CHILD", "mode": "YEARS", "amount": 25000, "years": 12}},
    {"id": "years_06", "text": "Noriu 5000 eur sutaupyti per metus.",
     "expected": {"mode": "YEARS", "amount": 5000, "years": 1}},
    {"id": "years_07", "text": "Per 6 metus noriu sukaupti 18000 eurų automobiliui.",
     "expected": {"goalType": "CAR", "mode": "YEARS", "amount": 18000, "years": 6}},
    {"id": "years_08", "text": "Reikia 100000 eurų per 20 metų namo pradiniam įnašui.",
     "expected": {"goalType": "HOME_DOWN_PAYMENT", "mode": "YEARS", "amount": 100000, "years": 20}},
    {"id": "years_09", "text": "Noriu per 4 metus atsidėti 15k eurų automobiliui.",
     "expected": {"goalType": "CAR", "mode": "YEARS", "amount": 15000, "years": 4}},
    {"id": "years_10", "text": "Sutaupyti 7000 eurų per 2 metus kitiems poreikiams.",
     "expected": {"goalType": "OTHER", "mode": "YEARS", "amount": 7000, "years": 2}},

    # --- 11-20: DATE režimas (konkreti data, santykinė "po N metų", vagus sezonas) -----------
    {"id": "date_01", "text": "Noriu sukaupti 30000 eurų iki 2032 metų automobiliui.",
     "expected": {"goalType": "CAR", "mode": "DATE", "amount": 30000, "targetYear": 2032}},
    {"id": "date_02", "text": "Reikia 20000 eurų namo įnašui iki 2030-ųjų.",
     "expected": {"goalType": "HOME_DOWN_PAYMENT", "mode": "DATE", "amount": 20000, "targetYear": 2030}},
    {"id": "date_03", "text": "Po 5 metų norėčiau turėti 40000 eurų.",
     "expected": {"mode": "DATE", "amount": 40000, "targetYear": 2031},
     "notes": "Santykinis terminas - targetYear = REFERENCE_DATE.year + 5, skaičiuojamas kode."},
    {"id": "date_04", "text": "Noriu sutaupyti 10000 eurų iki vasaros.",
     "expected": {"mode": "DATE", "amount": 10000, "targetYear": 2027, "targetMonth": 6},
     "notes": "'Vasara' be konkrečios datos - REFERENCE_DATE (lapkritis) > birželis, tad kiti metai."},
    {"id": "date_05", "text": "Po 3 metų planuoju turėti 12000 eurų automobiliui.",
     "expected": {"goalType": "CAR", "mode": "DATE", "amount": 12000, "targetYear": 2029}},
    {"id": "date_06", "text": "Iki rudens norėčiau susitaupyti 6000 eurų.",
     "expected": {"mode": "DATE", "amount": 6000, "targetYear": 2027, "targetMonth": 9}},
    {"id": "date_07", "text": "Noriu 50000 eurų iki 2035 m. namo pradiniam įnašui.",
     "expected": {"goalType": "HOME_DOWN_PAYMENT", "mode": "DATE", "amount": 50000, "targetYear": 2035}},
    {"id": "date_08", "text": "Po 7 metų reikės 35000 eurų automobiliui.",
     "expected": {"goalType": "CAR", "mode": "DATE", "amount": 35000, "targetYear": 2033}},
    {"id": "date_09", "text": "Iki žiemos norėčiau turėti 4000 eurų papildomai.",
     "expected": {"mode": "DATE", "amount": 4000, "targetYear": 2026, "targetMonth": 12},
     "notes": "Žiema (gruodis) nuo lapkričio - tais pačiais metais."},
    {"id": "date_10", "text": "Planuoju iki 2029 metų sutaupyti 22000 eurų.",
     "expected": {"mode": "DATE", "amount": 22000, "targetYear": 2029}},

    # --- 21-30: CHILD_AGE režimas (aiškus amžius, "pilnametis") -------------------------------
    {"id": "child_01", "text": "Vaikui dabar 4 metai, kai jam bus 18 norėtume turėti 20000 eurų.",
     "expected": {"goalType": "CHILD", "mode": "CHILD_AGE", "amount": 20000,
                  "childCurrentAge": 4, "childTargetAge": 18}},
    {"id": "child_02", "text": "Dukrai 2 metai, kai sulauks pilnametystės norime 25000 eurų.",
     "expected": {"goalType": "CHILD", "mode": "CHILD_AGE", "amount": 25000,
                  "childCurrentAge": 2, "childTargetAge": 18}},
    {"id": "child_03", "text": "Sūnui dabar 7 metukai, iki 18 reikia 15000 eurų studijoms.",
     "expected": {"goalType": "CHILD", "mode": "CHILD_AGE", "amount": 15000,
                  "childCurrentAge": 7, "childTargetAge": 18}},
    {"id": "child_04", "text": "Vaikas dabar 10 metų, kai jam bus 21 norime turėti 10000 eurų.",
     "expected": {"goalType": "CHILD", "mode": "CHILD_AGE", "amount": 10000,
                  "childCurrentAge": 10, "childTargetAge": 21}},
    {"id": "child_05", "text": "Dabar vaikui 1 metai, kai bus pilnametis - 30000 eurų.",
     "expected": {"goalType": "CHILD", "mode": "CHILD_AGE", "amount": 30000,
                  "childCurrentAge": 1, "childTargetAge": 18}},
    {"id": "child_06", "text": "Vaikui 15 metų, norim 19 sukakus turėti 8000 eurų.",
     "expected": {"goalType": "CHILD", "mode": "CHILD_AGE", "amount": 8000,
                  "childCurrentAge": 15, "childTargetAge": 19}},
    {"id": "child_07", "text": "Mažylis dabar 3 metų, kai jam sueis 18 reikės 17000 eurų.",
     "expected": {"goalType": "CHILD", "mode": "CHILD_AGE", "amount": 17000,
                  "childCurrentAge": 3, "childTargetAge": 18}},
    {"id": "child_08", "text": "Vaikui dabar 5, iki pilnametystes reikes 12000 eur.",
     "expected": {"goalType": "CHILD", "mode": "CHILD_AGE", "amount": 12000,
                  "childCurrentAge": 5, "childTargetAge": 18},
     "notes": "Be vienos diakritikos ('pilnametystes', 'reikes')."},
    {"id": "child_09", "text": "Dukrytei 0 metų, kai bus 18, norime 40000 eurų atsidėję.",
     "expected": {"goalType": "CHILD", "mode": "CHILD_AGE", "amount": 40000,
                  "childCurrentAge": 0, "childTargetAge": 18}},
    {"id": "child_10", "text": "Sūnui 12, tikslas - 20000 eurų iki jo 24-erių.",
     "expected": {"goalType": "CHILD", "mode": "CHILD_AGE", "amount": 20000,
                  "childCurrentAge": 12, "childTargetAge": 24}},

    # --- 31-38: WHEN režimas ("kada turėsiu...") ----------------------------------------------
    {"id": "when_01", "text": "Kada turėsiu 10000 eurų, jei atsidedu 100 eurų per mėnesį?",
     "expected": {"mode": "WHEN", "amount": 10000, "monthlyContribution": 100}},
    {"id": "when_02", "text": "Galiu atsidėti 200 eurų per mėnesį automobiliui, per kiek laiko sutaupysiu 15000 eurų?",
     "expected": {"goalType": "CAR", "mode": "WHEN", "amount": 15000, "monthlyContribution": 200}},
    {"id": "when_03", "text": "Jei kas mėnesį dedu 50 eurų, kada turėsiu 5000 eurų vaikui?",
     "expected": {"goalType": "CHILD", "mode": "WHEN", "amount": 5000, "monthlyContribution": 50}},
    {"id": "when_04", "text": "Atsidedu po 300 eurų kas mėnesį namo pradiniam įnašui, kada turėsiu 40000 eurų?",
     "expected": {"goalType": "HOME_DOWN_PAYMENT", "mode": "WHEN", "amount": 40000, "monthlyContribution": 300}},
    {"id": "when_05", "text": "Kada susitaupysiu 8000 eurų, jei kas mėnesį atsideda 80 eurų?",
     "expected": {"mode": "WHEN", "amount": 8000, "monthlyContribution": 80}},
    {"id": "when_06", "text": "Automobiliui reikia 12000 eurų, galiu dėti 150 eurų per mėnesį - kada turėsiu?",
     "expected": {"goalType": "CAR", "mode": "WHEN", "amount": 12000, "monthlyContribution": 150}},
    {"id": "when_07", "text": "Dedu 400 eurų per mėnesį, kada sukaupsiu 48000 eurų namo įnašui?",
     "expected": {"goalType": "HOME_DOWN_PAYMENT", "mode": "WHEN", "amount": 48000, "monthlyContribution": 400}},
    {"id": "when_08", "text": "Kada turėsiu 3000 eurų, jei atsidėsiu 25 eurus per mėnesį?",
     "expected": {"mode": "WHEN", "amount": 3000, "monthlyContribution": 25}},

    # --- 39-46: šnekamoji kalba / be diakritikų -----------------------------------------------
    {"id": "nodiac_01", "text": "Noriu sutaupyti 20000 euru per 5 metus automobiliui",
     "expected": {"goalType": "CAR", "mode": "YEARS", "amount": 20000, "years": 5}},
    {"id": "nodiac_02", "text": "Reikia 15000 euru namo pradiniam inasui per 8 metus",
     "expected": {"goalType": "HOME_DOWN_PAYMENT", "mode": "YEARS", "amount": 15000, "years": 8}},
    {"id": "nodiac_03", "text": "Vaikui dabar 4 metai kai jam bus 18 norim 20000 euru",
     "expected": {"goalType": "CHILD", "mode": "CHILD_AGE", "amount": 20000,
                  "childCurrentAge": 4, "childTargetAge": 18}},
    {"id": "nodiac_04", "text": "Kada turesiu 10000 euru jei atsidedu 100 euru per menesi",
     "expected": {"mode": "WHEN", "amount": 10000, "monthlyContribution": 100}},
    {"id": "nodiac_05", "text": "Noriu per 10 metu susitaupyti 30000 euru kazkokiam tikslui",
     "expected": {"goalType": "OTHER", "mode": "YEARS", "amount": 30000, "years": 10}},
    {"id": "nodiac_06", "text": "Po 5 metu noreciau tureti 40000 euru",
     "expected": {"mode": "DATE", "amount": 40000, "targetYear": 2031}},
    {"id": "nodiac_07", "text": "Iki vasaros reikia 10000 euru",
     "expected": {"mode": "DATE", "amount": 10000, "targetYear": 2027, "targetMonth": 6}},
    {"id": "nodiac_08", "text": "Sunui 7 metukai iki 18 reikia 15000 euru studijoms",
     "expected": {"goalType": "CHILD", "mode": "CHILD_AGE", "amount": 15000,
                  "childCurrentAge": 7, "childTargetAge": 18}},

    # --- 47-52: su rašybos klaidomis ------------------------------------------------------------
    {"id": "typo_01", "text": "Noriu sutaupit 20000 eurų per 5 metu automobilui",
     "expected": {"goalType": "CAR", "mode": "YEARS", "amount": 20000, "years": 5}},
    {"id": "typo_02", "text": "Reikia 15 tukst. eur namo pradiniam inasui per 8 metus",
     "expected": {"goalType": "HOME_DOWN_PAYMENT", "mode": "YEARS", "amount": 15000, "years": 8}},
    {"id": "typo_03", "text": "Vaikui dabr 4 metai kai jam bu 18 norim 20000 eur",
     "expected": {"goalType": "CHILD", "mode": "CHILD_AGE", "amount": 20000,
                  "childCurrentAge": 4, "childTargetAge": 18}},
    {"id": "typo_04", "text": "Kda turesiu 10000 eur jei atsidedu 100 eur per menesi",
     "expected": {"mode": "WHEN", "amount": 10000, "monthlyContribution": 100}},
    {"id": "typo_05", "text": "Noriu per 10 metu susitaupit 30000 eur kokiam tikslui",
     "expected": {"goalType": "OTHER", "mode": "YEARS", "amount": 30000, "years": 10}},
    {"id": "typo_06", "text": "Po 5 metu noriu tureti 40000 eur",
     "expected": {"mode": "DATE", "amount": 40000, "targetYear": 2031}},

    # --- 53-57: keli vaikai paminėti viename tekste (MVP = vienas tikslas vienu metu) ---------
    # Sprendimas (žr. PR aprašą): kai keli vaikai aiškiai atskirti (kuriam skirtas tikslas pasakyta
    # tiesiogiai), ekstrakcija turi pasirinkti TĄ vaiką normaliai. Kai TIKRAI neįmanoma nustatyti,
    # kuriam vaikui/kada tikslas skirtas, tie laukai šiame eval'e tiesiog NEVERTINAMI (expected
    # žodyne jų nėra) - tai eval'o apribojimas, ne extractoriaus elgesio specifikacija.
    {"id": "multi_child_01",
     "text": "Turime tris vaikus - 3, 7 ir 12 metų. Norime sutaupyti 50000 eurų ateičiai.",
     "expected": {"goalType": "CHILD", "amount": 50000},
     "notes": "Tikrai neaišku, kuriam vaikui/kada - mode/amžiai nevertinami."},
    {"id": "multi_child_02",
     "text": "Augame dviese vaikus, abu dar maži (2 ir 5 metų), norime sukaupti namo pradiniam "
             "įnašui 30000 eurų per 10 metų.",
     "expected": {"goalType": "HOME_DOWN_PAYMENT", "mode": "YEARS", "amount": 30000, "years": 10},
     "notes": "Vaikai paminėti kaip namų ūkio kontekstas, tikslas - ne vaikui, tad goalType != CHILD."},
    {"id": "multi_child_03",
     "text": "Turime du vaikus, vienam 6 kitam 9 metai - vyresniam iki pilnametystės reikės 15000 eurų.",
     "expected": {"goalType": "CHILD", "mode": "CHILD_AGE", "amount": 15000,
                  "childCurrentAge": 9, "childTargetAge": 18},
     "notes": "Ne tikrai ambiguous atvejis - 'vyresniam' aiškiai nurodo 9 metų vaiką."},
    {"id": "multi_child_04",
     "text": "Taupome keliems vaikams kartu - iš viso norime 60000 eurų per 15 metų.",
     "expected": {"goalType": "CHILD", "mode": "YEARS", "amount": 60000, "years": 15},
     "notes": "Suma bendra keliems vaikams, bet mode/amount lieka aiškūs (YEARS)."},
    {"id": "multi_child_05",
     "text": "Vaikai - 1, 5 ir 9 metų - norime jiems sutaupyti pinigų, bet tiksliai nenusprendėme "
             "kiek ir kada.",
     "expected": {"goalType": "CHILD"},
     "expect_missing_includes": ["amount"],
     "notes": "Suma ir terminas sąmoningai nepasakyti - clarifyingQuestion turėtų klausti sumos."},

    # --- 58-63: neaiškūs / nepilni atvejai -----------------------------------------------------
    {"id": "vague_01", "text": "Noriu kažkada susitaupyti pinigų.",
     "expected": {}, "expect_missing_includes": ["amount"],
     "notes": "Nieko konkretaus - goalType/mode nevertinami, bet amount TURI būti missingCritical."},
    {"id": "vague_02", "text": "Reikia pinigų automobiliui.",
     "expected": {"goalType": "CAR"}, "expect_missing_includes": ["amount"]},
    {"id": "vague_03",
     "text": "Turime šeimą, 4 žmonės, dirba 2, pajamos 2000, išlaidos 1500 - norime susitaupyti vaikui.",
     "expected": {"goalType": "CHILD", "members": 4, "workers": 2, "income": 2000, "expenses": 1500},
     "expect_missing_includes": ["amount"]},
    {"id": "vague_04", "text": "Norime 20000 eurų, bet nežinome per kiek laiko.",
     "expected": {"amount": 20000}, "expect_missing_includes": ["mode"]},
    {"id": "vague_05", "text": "Automobiliui per 5 metus.",
     "expected": {"goalType": "CAR", "mode": "YEARS", "years": 5},
     "expect_missing_includes": ["amount"]},
    {"id": "vague_06", "text": "Norime namo pradiniam įnašui, turime 2000 eurų pajamų ir 1500 eurų išlaidų.",
     "expected": {"goalType": "HOME_DOWN_PAYMENT", "income": 2000, "expenses": 1500},
     "expect_missing_includes": ["mode", "amount"]},

    # --- 64-65: namų ūkio laukai kartu su aiškiu tikslu ----------------------------------------
    {"id": "household_01",
     "text": "Šeima iš 4 žmonių, dirba abu tėvai, pajamos 2500 eurų, išlaidos 1800 eurų - norime "
             "sutaupyti vaikui 20000 eurų per 10 metų.",
     "expected": {"goalType": "CHILD", "mode": "YEARS", "amount": 20000, "years": 10,
                  "members": 4, "workers": 2, "income": 2500, "expenses": 1800}},
    {"id": "household_02",
     "text": "Gyvenam penkiese, dirba trys, pajamos 3200, islaidos 2400 - automobiliui reikia "
             "18000 euru per 6 metus.",
     "expected": {"goalType": "CAR", "mode": "YEARS", "amount": 18000, "years": 6,
                  "members": 5, "workers": 3, "income": 3200, "expenses": 2400}},
]
