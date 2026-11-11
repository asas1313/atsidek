# Taupymo tikslų modulis: nemokama / prisijungus versijų atskyrimas (prieš monetizaciją)

## Principai
- JOKIŲ investavimo patarimų: nevardijami produktai, instrumentai, fondai,
  bankai, taupymo būdai. Grąžos norma – tik naudotojo įvesta prielaida (default 0 %).
- Nemokamai naudotojas visada gauna pilną atsakymą į vieną klausimą.
- Prisijungus – personalizacija, išsaugojimas, keli tikslai, IV susiejimas.
- Visi skaičiavimai deterministiniai (Java). AI tik ištraukia duomenis ir paaiškina.
- Disclaimer prie kiekvieno rezultato: "Skaičiavimai informaciniai,
  tai nėra finansinis ar investavimo patarimas."
- Viskas pirma į staging.atsidek.eu.

## Teisių matrica
| Funkcija                         | Anonimas     | Prisijungęs (beta = premium) |
|----------------------------------|--------------|------------------------------|
| Vienas tikslas, skaičiavimas     | ✓            | ✓                            |
| Šablonai su LT statistika        | ✓            | ✓                            |
| Bendri patarimai                 | ✓            | ✓                            |
| AI įvestis laisvu tekstu         | dienos limitas | didesnis / neribotas       |
| Išsaugoti tikslai, progresas     | –            | SAVINGS_TRACKING             |
| Keli tikslai, paskirstymas       | –            | SAVINGS_MULTI_GOAL           |
| Scenarijai                       | –            | SAVINGS_SCENARIOS            |
| IV susiejimas                    | –            | SAVINGS_IV_LINK              |
| AI pokalbis apie planą           | –            | AI_PLAN_CHAT                 |
| PDF ataskaita                    | –            | PDF_EXPORT                   |

## Etapai

### 1. Skaičiavimo variklis (Java)
- SavingsEngine: grynos funkcijos (suma per mėn. / data / vaiko amžius → data),
  infliacijos korekcija, naudotojo grąžos prielaida
- Endpoint POST /api/v1/savings/plan (ta pati validacija kaip /compare:
  griežtas JSON, nežinomi laukai → 400 su priežastimi, BigDecimal ribos)
- Statistikos duomenys: versijuota lentelė/JSON su šaltiniu ir data
  kiekvienai reikšmei (pvz. vidutinė studijų kaina), lengvai atnaujinama
- Unit testai kraštutiniams atvejams (data praeityje, 0 suma, labai ilgas horizontas)

### 2. Nemokama UI dalis
- htmx forma + rezultatas + grafikas
- Šablonų puslapiai (atskiri URL, SEO meta), užpildyti statistikos reikšmėmis
- Bendrų patarimų blokas (statinis turinys)
- Aiškus kvietimas: "Išsaugok ir sek progresą – prisijunk"

### 3. Minimalus prisijungimas
- Palyginti ir pasirinkti (magic link el. paštu vs Spring Security su slaptažodžiu
  vs Keycloak) pagal paprastumą vienam kūrėjui
- users lentelė, sesijos, atsijungimas, paskyros ištrynimas (BDAR)

### 4. Teisių (entitlements) sluoksnis
- entitlement(user_id, feature, source, valid_from, valid_until)
- source = BETA dabar, vėliau PAYMENT
- Viena patikros vieta Java pusėje; Python AI servisas teisių klausia per API,
  pats jų nesprendžia
- Feature flag: beta režimas (visi prisijungę = premium) įjungiamas/išjungiamas be deploy

### 5. Premium funkcijos (tokia eilės tvarka)
1. Išsaugoti tikslai + progreso žymėjimas (savings_goal, savings_contribution)
2. Keli tikslai + prioritetai + mėnesio sumos paskirstymas
3. Scenarijai
4. IV susiejimas (naudoja esamą TaxEngine)
5. PDF ataskaita

### 6. AI sluoksnis (Python)
- Ištraukimas iš laisvo teksto į SavingsInput (tas pats šablonas kaip istraukimas.py)
- Sistemos prompt'e griežtas draudimas: jokių produktų, instrumentų,
  "kur laikyti pinigus" rekomendacijų; tokiems klausimams – mandagus atsisakymas
  ir grąžinimas prie skaičiavimo
- Evals rinkinys: įprasti tikslai, dviprasmiški atvejai, ir provokacijos
  ("į ką investuoti?", "koks bankas geriausias?", prompt injection) –
  testas krenta, jei atsakyme yra produkto/instrumento rekomendacija
- AI kaštų apskaita kiekvienai užklausai

### 7. Matavimai
- Įvykiai: skaičiavimas atliktas, šablonas atidarytas, paspausta
  "pridėti antrą tikslą" / "išsaugoti", registracija, grįžimas po 7/30 d.,
  kiekvienos premium funkcijos naudojimas
- Be trečiųjų šalių sekimo; savo lentelėje arba privatumui draugiškas įrankis

## DB eskizas
- users(id, email, created_at, deleted_at)
- savings_goal(id, user_id, type, title, target_amount, target_date,
  priority, assumptions jsonb, created_at)
- savings_contribution(id, goal_id, month, amount)
- entitlement(id, user_id, feature, source, valid_from, valid_until)
- stats_value(key, value, unit, source, source_date, version)

## Kriterijai prieš įjungiant mokėjimus
- Nemokama dalis veikia ir renka srautą bent kelias savaites
- Yra registruotų naudotojų, kurie grįžta žymėti progreso
- Aišku, kurios 1–2 premium funkcijos naudojamos dažniausiai –
  būtent jos taps mokamos, likusios gali likti prisijungusiems nemokamai

## Atviri klausimai
- Prisijungimo būdas
- Anonimo AI limito dydis
- Ar progreso sekimas lieka nemokamas prisijungusiems (traukia grįžti),
  o mokami tik keli tikslai + IV + scenarijai