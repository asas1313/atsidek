# Atsidėk – banko išrašų importo ir pajamų sekimo papildinys

Nov 4, 2026 · @Andrius

## 1. Apžvalga

Papildinys leidžia IV turėtojui įkelti banko išrašą ir automatiškai gauti veiklos pajamas bei sumą, kurią šį mėnesį reikia atsidėti mokesčiams. Tai pirmoji mokamo plano funkcija, nes ji duoda vertę kiekvieną mėnesį, o ne vieną kartą per metus.

**Problema.** IV turėtojai pajamas skaičiuoja rankomis iš internetinės bankininkystės arba jų visai neseka. Nuo 2026 m. reformos visos metinės pajamos sumuojamos, todėl klaida kainuoja daugiau.

**Sprendimas.** Vartotojas kartą per mėnesį įkelia išrašą (camt.053 arba banko CSV). Sistema atpažįsta gautus mokėjimus, pasiūlo, kurie yra veiklos pajamos, vartotojas patvirtina, ir Atsidėk perskaičiuoja atsidėjimo sumą.

**Sėkmės matas.** Vartotojas pirmą išrašą apdoroja per mažiau nei 3 minutes, o po 3 mėnesių automatinis klasifikavimas be rankinių pataisymų teisingai priskiria bent 90 % operacijų.

**Technologijos.** Specifikacija nepriklauso nuo technologijų; pavyzdžiai pateikti Java 21 / Spring Boot stiliumi. Jei Atsidėk naudoja kitą steką, struktūra lieka ta pati.

## 2. Vartotojų istorijos ir apimtis

| ID | Kaip vartotojas noriu… | Kad… |
| --- | --- | --- |
| US-1 | įkelti banko išrašą (camt.053 ar CSV) | nereikėtų pajamų perrašinėti rankomis |
| US-2 | matyti pasiūlymą, kurie gauti mokėjimai yra veiklos pajamos | galėčiau tik patvirtinti, o ne rūšiuoti |
| US-3 | pažymėti mokėtoją kaip „visada pajamos“ ar „niekada pajamos“ | kitą mėnesį sistema tai žinotų pati |
| US-4 | matyti mėnesio ir metų pajamų suvestinę | žinočiau, kur esu tarifų ribų atžvilgiu |
| US-5 | gauti sumą „atsidėk šį mėnesį X EUR“ | žinočiau, kiek pervesti į taupymo sąskaitą |
| US-6 | įkelti kelių bankų išrašus | matytų visas pajamas vienoje vietoje |
| US-7 | ištrinti įkeltus duomenis | kontroliuočiau savo informaciją |

**MVP apima:** US-1–US-7; formatus camt.053 (ISO 20022), Swedbank, SEB, Revolut, Paysera CSV; rankinį įkėlimą per naršyklę; tik EUR operacijas.

**Neįeina į MVP:** automatinis duomenų gavimas per open banking API; PDF išrašai; išlaidų sekimas (IV su faktinėmis sąnaudomis); kitos valiutos nei EUR; MT940; mobilioji programėlė. Šie punktai įvertinami po MVP pagal vartotojų prašymus.

## 3. Funkciniai reikalavimai

Apdorojimas vyksta septyniais nuosekliais žingsniais; kiekvienas turi aiškų įėjimą ir išėjimą, kad juos būtų galima testuoti atskirai.

1. **Įkėlimas (FR-1).** Vartotojas įkelia vieną ar kelis failus (iki 10 MB kiekvienas). Priimami `.xml`, `.csv`, `.txt`. Failas apdorojamas atmintyje; originalas į diską ar duomenų bazę nerašomas.
2. **Formato atpažinimas (FR-2).** Sistema nustato formatą automatiškai: XML su `camt.053` vardų erdve arba CSV pagal antraštės eilutės parašą (žr. 4 skyrių). Neatpažinus formato, rodomas aiškus pranešimas ir pasiūlymas pranešti apie naują banką.
3. **Analizė ir normalizavimas (FR-3).** Kiekviena eilutė paverčiama į normalizuotą operaciją (5 skyrius). Klaidingos eilutės nepertraukia viso failo: jos surenkamos į klaidų sąrašą su eilutės numeriu.
4. **Dublikatų šalinimas (FR-4).** Ta pati operacija, įkelta antrą kartą (kitas failas, persidengiantis laikotarpis), neįrašoma. Unikalumo raktas aprašytas 5 skyriuje.
5. **Klasifikavimas (FR-5).** Kiekvienai įeinančiai operacijai priskiriama kategorija ir pasitikėjimo įvertis (6 skyrius). Išeinančios operacijos MVP saugomos, bet neklasifikuojamos.
6. **Patvirtinimas (FR-6).** Vartotojas mato pasiūlymus, sugrupuotus pagal mokėtoją, ir gali patvirtinti visus vienu paspaudimu arba pakeisti pavienius. Pakeitimas gali būti išsaugotas kaip taisyklė (US-3).
7. **Suvestinė (FR-7).** Po patvirtinimo perskaičiuojamos mėnesio ir metų pajamos bei atsidėjimo suma (7 skyrius).

**Papildomi reikalavimai:**

- FR-8: vartotojas gali turėti kelias banko sąskaitas; sąskaita identifikuojama pagal IBAN iš išrašo arba, jei jo nėra (kai kurie CSV), vartotojo pasirinkimu.
- FR-9: vidiniai pervedimai tarp vartotojo sąskaitų atpažįstami ir nelaikomi pajamomis (abiejų pusių IBAN priklauso vartotojui).
- FR-10: vartotojas gali ištrinti vieną importą (su visomis jo operacijomis) arba visus duomenis.

## 4. Palaikomi formatai

Pirmenybė teikiama camt.053, nes jis standartizuotas ir jį eksportuoja dauguma Lietuvos bankų; CSV adapteriai reikalingi fintech įstaigoms. Kiekvienas formatas yra atskiras adapteris, įgyvendinantis tą pačią sąsają.

```java
public interface StatementParser {
    boolean supports(byte[] head, String fileName); // formato atpažinimas pagal pirmus ~4 KB
    ParseResult parse(InputStream in);             // operacijos + klaidų sąrašas
}
```

### 4.1. camt.053 (ISO 20022)

Palaikyti versijas `camt.053.001.02` ir `camt.053.001.08`. Versija `.08` šalių duomenis apgaubia papildomu `Pty` elementu, todėl XPath skiriasi.

| Normalizuotas laukas | camt.053.001.02 kelias | Pastaba |
| --- | --- | --- |
| accountIban | `Stmt/Acct/Id/IBAN` |  |
| amount, currency | `Ntry/Amt`, atributas `Ccy` |  |
| direction | `Ntry/CdtDbtInd` | `CRDT` = įeinanti |
| bookingDate | `Ntry/BookgDt/Dt` arba `DtTm` |  |
| status | `Ntry/Sts` | imti tik `BOOK` |
| bankRef | `Ntry/AcctSvcrRef` | dublikatų raktui |
| endToEndId | `NtryDtls/TxDtls/Refs/EndToEndId` | `NOTPROVIDED` laikyti tuščiu |
| counterpartyName | `TxDtls/RltdPties/Dbtr/Nm` | `.08`: `Dbtr/Pty/Nm` |
| counterpartyIban | `TxDtls/RltdPties/DbtrAcct/Id/IBAN` |  |
| remittance | `TxDtls/RmtInf/Ustrd` | kelis sujungti tarpu |
| structuredRef | `TxDtls/RmtInf/Strd/CdtrRefInf/Ref` | RF nuoroda |

Jei viename `Ntry` yra keli `TxDtls` (paketinis įrašas), kiekvienas `TxDtls` tampa atskira operacija, o suma imama iš `TxDtls/Amt`. Išeinančioms operacijoms (`DBIT`) šalis yra `Cdtr`, ne `Dbtr`.

### 4.2. CSV adapteriai

CSV formatai nėra standartizuoti ir keičiasi be įspėjimo. Stulpelių pavadinimai **turi būti nustatyti iš realių, nuasmenintų pavyzdinių failų** prieš rašant adapterį; žemiau nurodyta, ką kiekviename reikia rasti.

| Šaltinis | Ką rasti faile | Žinomi niuansai |
| --- | --- | --- |
| Swedbank | data, suma, D/K požymis, mokėtojas, paskirtis, sąskaitos nr. | patikrinti, ar verta naudoti camt.053 vietoje CSV |
| SEB | tie patys laukai | patikrinti kodavimą ir skyriklį |
| Revolut | data, suma su ženklu, aprašymas, valiuta, būsena | asmeninis ir verslo eksportas skiriasi; imti tik užbaigtas |
| Paysera | data, suma, mokėtojas, paskirtis | patikrinti, ar siūlomas ir XML eksportas |

**Bendros CSV taisyklės:** kodavimas UTF-8 su BOM arba be jo, taip pat Windows-1257 (lietuviškos raidės); skyriklis `;` arba `,` nustatomas automatiškai; dešimtainis skyriklis gali būti kablelis; datos formatai `yyyy-MM-dd` ir `dd.MM.yyyy`. Formato atpažinimas remiasi antraštės eilutės parašu (pvz., privalomų stulpelių rinkiniu).

## 5. Normalizuota operacija ir duomenų modelis

Visi adapteriai grąžina tą pačią operacijos struktūrą; likusi sistema niekada nemato originalaus formato.

```java
public record NormalizedTransaction(
    String accountIban,          // vartotojo sąskaita
    LocalDate bookingDate,
    BigDecimal amount,           // visada teigiama; kryptis atskirai
    String currency,             // MVP: tik "EUR"
    Direction direction,         // IN | OUT
    String counterpartyName,     // gali būti null
    String counterpartyIban,     // gali būti null
    String remittance,           // laisvo teksto paskirtis
    String structuredRef,        // RF ar kita struktūrizuota nuoroda
    String bankRef,              // AcctSvcrRef ar CSV ID
    String endToEndId,
    String sourceFormat          // "camt053.02", "revolut-csv" ir t. t.
) {}
```

Sumos saugomos `BigDecimal` / `NUMERIC(14,2)`, niekada `double`.

### 5.1. Lentelės

| Lentelė | Pagrindiniai laukai | Paskirtis |
| --- | --- | --- |
| `bank_account` | id, user\_id, iban, label, bank\_name | vartotojo sąskaitos |
| `statement_import` | id, user\_id, account\_id, source\_format, period\_from, period\_to, tx\_count, created\_at | vienas įkėlimas; trinant importą trinamos ir jo operacijos |
| `transaction` | id, user\_id, import\_id, account\_id, booking\_date, amount, direction, counterparty\_name, counterparty\_iban\_hash, remittance, dedup\_key, category, confidence, confirmed, rule\_id | normalizuotos operacijos |
| `classification_rule` | id, user\_id, match\_type, match\_value, category, created\_at | vartotojo taisyklės (US-3) |
| `monthly_summary` | user\_id, year, month, business\_income, set\_aside\_amount, calculated\_at | apskaičiuota suvestinė; galima perskaičiuoti bet kada |

**Kategorijos (`category`):** `BUSINESS_INCOME`, `INTERNAL_TRANSFER`, `PERSONAL` (dovanos, grąžinimai, paskolos), `SALARY` (darbo užmokestis, sumuojamas atskirai), `UNKNOWN`.

### 5.2. Dublikatų raktas

`dedup_key = SHA-256(account_iban | booking_date | amount | direction | coalesce(bank_ref, end_to_end_id, normalized(remittance)))`. Unikalus indeksas `(user_id, dedup_key)`; pakartotinis įrašas tyliai praleidžiamas ir įskaičiuojamas į importo ataskaitą kaip „jau buvo“.

### 5.3. Ko nesaugoti

Originalus failas nesaugomas. Mokėtojo IBAN saugomas tik kaip druskinta maiša (`counterparty_iban_hash`), nes jo reikia tik taisyklėms sutapdinti. Mokėtojo vardas ir paskirtis saugomi, nes vartotojas juos mato patvirtinimo ekrane; žr. 11 skyrių dėl saugojimo termino.

## 6. Pajamų klasifikavimas

MVP naudoja deterministines taisykles be ML: pirmoji suveikusi taisyklė nustato kategoriją ir pasitikėjimą. Taip rezultatą visada galima paaiškinti vartotojui („priskirta, nes…“).

Taisyklės taikomos tik įeinančioms (`IN`) operacijoms, šia tvarka:

1. **Vartotojo taisyklė** pagal mokėtojo IBAN maišą arba vardą → jos kategorija, pasitikėjimas 1.0.
2. **Vidinis pervedimas:** mokėtojo IBAN priklauso vienai iš vartotojo sąskaitų, arba vardas sutampa su vartotojo vardu ir paskirtyje yra „savo sąskaita“, „pervedimas“ → `INTERNAL_TRANSFER`, 0.95.
3. **Darbo užmokestis:** paskirtyje yra „darbo užmokestis“, „DU“, „atlyginimas“, „salary“ → `SALARY`, 0.8.
4. **Grąžinimai ir asmeniniai:** paskirtyje yra „grąžinimas“, „refund“, „dovana“, „paskola“, arba mokėtojas yra žinoma valstybės institucija (VMI, Sodra grąžinimai) → `PERSONAL`, 0.7.
5. **Sąskaitos požymis:** paskirtyje yra „sąskaita“, „SF“, „invoice“, „PVM SF“ ar sąskaitos numerio šablonas, arba yra struktūrizuota RF nuoroda → `BUSINESS_INCOME`, 0.85.
6. **Mokėtojas yra juridinis asmuo:** varde yra UAB, MB, AB, IĮ, VšĮ, Ltd, GmbH, OÜ, SIA ir pan. → `BUSINESS_INCOME`, 0.75.
7. **Istorija (tikrinama prieš 3–6 punktus):** tas pats mokėtojas anksčiau patvirtintas kaip pajamos → `BUSINESS_INCOME`, 0.9.
8. Kitaip → `UNKNOWN`, 0.0; vartotojas turi pasirinkti.

Taisyklių raktažodžiai saugomi konfigūracijoje (YAML), ne kode, kad juos būtų galima papildyti be išleidimo. Teksto palyginimas be didžiųjų raidžių ir be lietuviškų diakritikų („saskaita“ = „sąskaita“).

**Patvirtinimo logika:** operacijos su pasitikėjimu ≥ 0.85 rodomos jau pažymėtos; 0.5–0.85 rodomos su pasiūlymu, bet reikalauja patvirtinimo; < 0.5 rodomos viršuje kaip „reikia jūsų sprendimo“. Į pajamas skaičiuojamos tik patvirtintos (`confirmed = true`) operacijos.

**Taisyklės išsaugojimas:** kai vartotojas pakeičia kategoriją, siūloma „Visada taip žymėti mokėtoją X“. Sutikus sukuriama `classification_rule`, ir ji iškart pritaikoma kitoms to paties importo operacijoms.

## 7. Ryšys su atsidėjimo skaičiavimu

Papildinys mokesčių neskaičiuoja pats: jis tik paduoda patvirtintas pajamas į esamą Atsidėk skaičiavimo modulį. Taip mokesčių logika lieka vienoje vietoje.

```java
public interface TaxEstimator {
    TaxEstimate estimate(TaxInput input);
}

public record TaxInput(
    int year,
    BigDecimal businessIncomeYtd,   // patvirtintos BUSINESS_INCOME sumos nuo metų pradžios
    BigDecimal salaryIncomeYtd,     // SALARY, nes nuo 2026 m. pajamos sumuojamos
    BigDecimal expensesYtd,         // MVP: 0, naudojamos išlaidos pagal vartotojo profilį
    UserTaxProfile profile          // pvz., ar taiko faktines sąnaudas, kiti nustatymai
) {}

public record TaxEstimate(
    BigDecimal gpm, BigDecimal vsd, BigDecimal psd,
    BigDecimal totalYtd
) {}
```

**Mėnesio atsidėjimo suma:** `setAside(month) = estimate(iki mėnesio pabaigos).totalYtd − estimate(iki ankstesnio mėnesio pabaigos).totalYtd`. Taip progresiniai tarifai ir ribos teisingai atsispindi tame mėnesyje, kai jos pasiekiamos.

**Tarifai ir ribos** saugomi metų konfigūracijoje (pvz., `tax-rules/2026.yaml`, `tax-rules/2027.yaml`), ne kode. Naujų metų taisyklės pridedamos kaip naujas failas su testais.

**Perskaičiavimas:** suvestinė perskaičiuojama po kiekvieno patvirtinimo, taisyklės pakeitimo ar importo ištrynimo. Skaičiavimas pigus, todėl rezultatas `monthly_summary` lentelėje yra tik talpykla.

## 8. API specifikacija

Visi galiniai taškai reikalauja autentifikacijos ir aktyvaus mokamo plano (išskyrus peržiūrą, žr. 10 skyrių). Vartotojas mato tik savo duomenis: `user_id` imamas iš sesijos, niekada iš užklausos.

| Metodas | Kelias | Paskirtis | Atsakymas |
| --- | --- | --- | --- |
| POST | `/api/imports` | įkelti failą (`multipart/form-data`, laukas `file`, neprivalomas `accountId`) | `201` + importo ataskaita |
| GET | `/api/imports` | importų sąrašas | `200` |
| DELETE | `/api/imports/{id}` | ištrinti importą ir jo operacijas | `204` |
| GET | `/api/transactions?from=&to=&status=pending` | operacijos, filtruojamos pagal datą ir patvirtinimą | `200` |
| PATCH | `/api/transactions/{id}` | pakeisti kategoriją, patvirtinti | `200` |
| POST | `/api/transactions/confirm` | patvirtinti kelias (`{ids:[…]}`) | `200` |
| GET/POST/DELETE | `/api/rules` | vartotojo taisyklės | `200` / `201` / `204` |
| GET | `/api/summary?year=2026` | mėnesių pajamos ir atsidėjimo sumos | `200` |
| DELETE | `/api/bank-data` | ištrinti visus banko duomenis | `204` |

**Importo ataskaitos pavyzdys:**

```json
{
  "importId": "9b2f…",
  "format": "camt053.02",
  "accountIban": "LT12 7300 0101 2345 6789",
  "period": { "from": "2026-10-01", "to": "2026-10-31" },
  "parsed": 142,
  "incoming": 23,
  "duplicatesSkipped": 0,
  "needsReview": 4,
  "errors": [ { "line": 57, "message": "Neatpažinta data: 31/10/26" } ]
}
```

**Klaidos:** `400` neatpažintas formatas (su `code: UNSUPPORTED_FORMAT`), `413` per didelis failas, `422` failas atpažintas, bet be nė vienos tinkamos operacijos, `402` reikia mokamo plano. Klaidų tekstas lietuviškas, skirtas rodyti vartotojui.

## 9. Vartotojo sąsaja

Pagrindinis srautas turi tilpti į tris ekranus ir trukti mažiau nei 3 minutes.

1. **Įkėlimas.** Didelė „tempk failą čia“ zona, po ja trumpa instrukcija kiekvienam bankui (kur internetinėje bankininkystėje rasti XML ar CSV eksportą; tikslų kelią reikia patikrinti kiekviename banke) su nuoroda į išsamesnį straipsnį. Instrukcijos yra SEO turinys, todėl kiekviena turi atskirą viešą puslapį.
2. **Peržiūra ir patvirtinimas.** Viršuje „Reikia jūsų sprendimo“ (UNKNOWN ir žemas pasitikėjimas), žemiau sugrupuota pagal mokėtoją: vardas, operacijų skaičius, suma, pasiūlyta kategorija, paaiškinimas („priskirta, nes mokėtojas yra UAB“). Mygtukai: „Patvirtinti viską“, kategorijos pasirinkimas eilutėje, „Visada taip žymėti“.
3. **Rezultatas.** Didelis skaičius: „Šį mėnesį atsidėk 412,30 EUR“, po juo mėnesio pajamos, metų pajamos ir juosta, rodanti padėtį tarifų ribų atžvilgiu.

**Kiti ekranai:** importų istorija su trynimu; taisyklių sąrašas su redagavimu ir trynimu; nustatymuose mygtukas „Ištrinti visus banko duomenis“.

**Tekstai:** paprasta kalba, be „camt.053“ ar „normalizavimo“ vartotojui matomose vietose. Sumos formatu `1 234,56 EUR`, datos `2026-10-31`.

**Mobilusis vaizdas:** įkėlimas ir rezultatas turi veikti telefone (failas iš banko programėlės), patvirtinimo sąrašas gali būti supaprastintas.

## 10. Prieigos kontrolė ir mokamas planas

Nemokamas vartotojas gali pamatyti vertę, bet ne ja naudotis nuolat: peržiūra nemokama, išsaugojimas ir suvestinė mokami.

| Galimybė | Nemokamas | Mokamas |
| --- | --- | --- |
| Įkelti išrašą ir matyti klasifikavimo peržiūrą | taip, 1 kartą, be išsaugojimo | taip |
| Patvirtinti ir išsaugoti operacijas | ne | taip |
| Mėnesio atsidėjimo suma iš išrašo | rodoma tik bendra pajamų suma | taip |
| Vartotojo taisyklės | ne | taip |
| Kelios sąskaitos | ne | iki 5 |

**Įgyvendinimas:** vienas `Entitlement` patikrinimas serverio pusėje (`@RequiresPlan(PREMIUM)` ar atitikmuo) kiekviename mokamame galiniame taške; vartotojo sąsajos paslėpimas nėra apsauga. Plano būsena saugoma `user_subscription` lentelėje ir atnaujinama per mokėjimų tiekėjo webhook.

**Mokėjimai:** Stripe arba Paddle (Paddle tvarko PVM kaip pardavėjas, todėl paprasčiau, jei planuojama pardavinėti ir už Lietuvos ribų). Planai: metinis ir mėnesinis; ankstyvųjų vartotojų kuponas. Pasibaigus prenumeratai duomenys lieka 30 dienų tik skaitymui, vėliau ištrinami, apie tai įspėjus el. paštu.

**Kaina** nėra šios užduoties dalis; ji konfigūruojama mokėjimų tiekėjo pusėje.

## 11. Saugumas ir GDPR

Banko išrašuose yra ne tik vartotojo, bet ir trečiųjų asmenų (mokėtojų) asmens duomenų, todėl taikomas duomenų mažinimo principas: saugoma tik tai, ko reikia funkcijai.

**XML analizė (kritinė):** išjungti išorines esybes ir DTD, kad būtų išvengta XXE atakų.

```java
XMLInputFactory f = XMLInputFactory.newFactory();
f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
// StAX srautinė analizė: nekelti viso failo į DOM
```

**Kiti saugumo reikalavimai:**

- Failo dydžio riba 10 MB ir eilučių riba (pvz., 50 000), kad būtų apsisaugota nuo išteklių išnaudojimo; ZIP failai MVP nepriimami.
- CSV eksportuojant atgal (jei bus) apsaugoti nuo formulių injekcijos: reikšmes, prasidedančias `=`, `+`, `-`, `@`, pažymėti.
- Duomenų bazė ir atsarginės kopijos šifruojamos; ryšys tik HTTPS.
- Žurnaluose (log) nerašyti IBAN, vardų, sumų ir paskirties teksto; rašyti tik importo ID ir skaičius.
- Visi užklausų filtrai pagal `user_id` iš sesijos; integraciniai testai tikrina, kad vartotojas A negauna B duomenų.

**GDPR:**

- Atnaujinti privatumo politiką: kokie duomenys iš išrašų saugomi, kam (pajamų skaičiavimui), kiek laiko, kaip ištrinti.
- Teisinis pagrindas: sutarties vykdymas (vartotojas pats įkelia duomenis paslaugai gauti). Mokėtojų duomenims: teisėtas interesas, apribotas iki būtiniausių laukų. Rekomenduojama šį vertinimą patvirtinti su teisininku.
- Saugojimo terminas: kol aktyvi paskyra ir einamieji bei praėję mokestiniai metai (deklaracijai); senesni metai automatiškai ištrinami arba agreguojami į sumas.
- Teisė būti pamirštam: `DELETE /api/bank-data` ir paskyros trynimas pašalina operacijas, taisykles ir suvestines, įskaitant atsarginių kopijų rotacijos terminą, nurodytą politikoje.
- Duomenys talpinami ES.

## 12. Nefunkciniai reikalavimai

| Sritis | Reikalavimas |
| --- | --- |
| Našumas | 5 000 operacijų failas apdorojamas per < 3 s serveryje; atsakymas vartotojui < 5 s |
| Atmintis | srautinė analizė (StAX, eilutė po eilutės CSV); vienas importas < 100 MB RAM |
| Patikimumas | importas vyksta vienoje transakcijoje: arba įrašomos visos tinkamos operacijos, arba nė viena |
| Idempotentiškumas | tas pats failas įkeltas du kartus nesukuria dublikatų (5.2 skyrius) |
| Tikslumas | sumos be apvalinimo klaidų (`BigDecimal`); suvestinės suma = patvirtintų operacijų suma iki cento |
| Stebėsena | matuoti: importų skaičių pagal formatą, nepavykusių importų dalį, vidutinį `needsReview` skaičių, vidutinį laiką iki patvirtinimo |
| Plečiamumas | naujas CSV adapteris pridedamas viena klase ir testų failais, nekeičiant kito kodo |
| Prieinamumas | sąsaja veikia su klaviatūra; spalvos nėra vienintelis kategorijos požymis |

**Klaidų stebėsena:** kai formatas neatpažintas arba > 10 % eilučių nepavyko, siųsti vidinį pranešimą su formato parašu (antraštės eilute be duomenų), kad nauji ar pasikeitę bankų formatai būtų pastebėti greitai.

## 13. Testavimas ir priėmimo kriterijai

**Testų duomenys:** kiekvienam formatui bent 3 realūs, nuasmeninti failai (vardai, IBAN ir paskirtys pakeisti, struktūra išlaikyta) kataloge `src/test/resources/statements/<formatas>/`, kiekvienas su laukiamu rezultatu JSON („golden“ testas). Niekada nekelti realių nenuasmenintų išrašų į repozitoriją.

**Testų lygiai:**

- Vienetų testai: kiekvienas adapteris, formato atpažinimas, dublikatų raktas, kiekviena klasifikavimo taisyklė, lietuviškų diakritikų normalizavimas.
- Kraštiniai atvejai: tuščias failas, tik išeinančios operacijos, paketinis `Ntry`, `NOTPROVIDED` nuorodos, Windows-1257 kodavimas, kablelis kaip dešimtainis skyriklis, neigiamos sumos Revolut CSV, persidengiantys laikotarpiai.
- Saugumo testai: XXE bandymas camt.053 faile turi būti atmestas; vartotojas A negali skaityti, keisti ar trinti B duomenų.
- Integracinis: įkėlimas → patvirtinimas → suvestinė, patikrinant atsidėjimo sumą su rankomis apskaičiuota.

**Priėmimo kriterijai:**

- [ ] Visi 5 MVP formatai atpažįstami automatiškai ir analizuojami be klaidų testų failuose.
- [ ] Tas pats failas įkeltas antrą kartą prideda 0 operacijų.
- [ ] Vidinis pervedimas tarp dviejų vartotojo sąskaitų nelaikomas pajamomis.
- [ ] Vartotojo taisyklė pritaikoma tame pačiame ir vėlesniuose importuose.
- [ ] Atsidėjimo suma sutampa su esamu Atsidėk skaičiuokle, kai įvedamos tos pačios pajamos.
- [ ] Nemokamas vartotojas mato peržiūrą, bet negali išsaugoti; serveris grąžina `402`.
- [ ] „Ištrinti visus banko duomenis“ palieka 0 susijusių įrašų duomenų bazėje.
- [ ] Žurnaluose nėra IBAN, vardų ar sumų.
- [ ] Pirmas importas nuo įkėlimo iki rezultato užtrunka < 3 min. testuojant su 3 nepriklausomais vartotojais.

## 14. Įgyvendinimo etapai ir atviri klausimai

Tikslas: papildinys veikia iki vasario pradžios, kai prasideda deklaravimo sezonas. Įverčiai skaičiuoti dirbant apie 10–15 val. per savaitę su Claude Code.

1. **Pagrindas (1 savaitė):** duomenų modelis, `StatementParser` sąsaja, camt.053 adapteris (abi versijos), dublikatų raktas, XXE apsauga, testų karkasas.
2. **CSV ir klasifikavimas (1–2 savaitės):** surinkti pavyzdinius failus, 4 CSV adapteriai, klasifikavimo taisyklės iš YAML, vartotojo taisyklės.
3. **Sąsaja ir skaičiavimas (1–2 savaitės):** trys ekranai, patvirtinimo srautas, ryšys su `TaxEstimator`, mėnesio suvestinė.
4. **Mokamas planas ir paleidimas (1 savaitė):** prieigos kontrolė, mokėjimų webhook, privatumo politika, instrukcijų puslapiai kiekvienam bankui, testavimas su 3 vartotojais.

Iš viso apie 4–6 savaitės. Jei laiko trūksta, pirmiausia atsisakyti CSV adapterių, paliekant camt.053 ir Revolut.

**Atviri klausimai:**

- [ ] Kokiu steku parašytas dabartinis Atsidėk ir kur gyvena mokesčių skaičiavimo logika (serveris ar naršyklė)?
- [ ] Ar dabartinis skaičiavimo modulis jau palaiko visų pajamų sumavimą pagal 2026 m. reformą?
- [ ] Kurie bankai ir fintech įstaigos tikrai eksportuoja camt.053, o kurie tik CSV? Patikrinti savo sąskaitose.
- [ ] Ar vartotojų paskyros ir autentifikacija jau egzistuoja, ar jas reikia kurti kartu?
- [ ] Stripe ar Paddle? Priklauso nuo to, ar planuojama pardavinėti tik Lietuvoje.
- [ ] Teisinio pagrindo mokėtojų duomenims patvirtinimas su teisininku.
