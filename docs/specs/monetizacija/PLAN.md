# PLAN.md – AI naudojimo limitai ir mokėjimai

> Planas, ne implementacija. Nieko iš čia aprašyto dar nėra parašyta kode. Prielaidos apie projektą, kurių negalėjau patikrinti kodu, pažymėtos **PRIELAIDA:**.

## 0. Santrauka

Tikslas – kad AI (Claude API) kaštai ir hostingas atsipirktų. Bazinė skaičiuoklė ir straipsniai lieka nemokami (SEO/srautas). Mokama/ribojama yra tik tai, kas kviečia Claude API: dabar `assistant/` (IV mokesčių asistentas), netrukus ir planuojamas „Taupymo tikslų“ modulis (`docs/specs/taupymo-tikslai/`).

Rekomenduojama tvarka (detaliau F skyriuje): **A (limitai) pirmas ir atsiperka iškart, be mokėjimų** → **B (paskyros, Google OAuth atverimas visiems)** → **C (vienas mokėjimo būdas + teisės)**. Tai sutampa su jūsų nurodytu prioritetu.

## 1. Esama būklė (ką radau repozitorijoje)

- **Autentifikacija šiandien yra tik administratoriui, ne vartotojams.** `eu.atsidek.auth.SecurityConfig` – Google OAuth2 (Spring Security), bet `atsidek.auth.allowed-emails` sąrašas riboja prisijungimą iki konkrečių el. paštų (arba vieno savininko – `owner-email`). Tai naudojama tik banko išrašų importo moduliui (`eu.atsidek.bank.*`, pagal README – vieno vartotojo MVP, pagal nutylėjimą išjungtas). **Viešų registracijų/paskyrų sistemos nėra.** README tiesiai sako: „Nepadaryta: magic link, mokėjimai ir plano tikrinimas (402)".
- **AI asistentas (`assistant/`, Python/FastAPI) yra pilnai anoniminis.** Vienintelė esama apsauga – `assistant/app/limiter.py`: atminties (ne DB) ribotuvas – slenkantis 1 val. langas vienam IP (`AI_RATE_LIMIT_PER_HOUR`, numatyta 10) + bendras paros limitas (`AI_DAILY_CAP`, numatyta 300). **Trūkumai:** skaitiklis nulinamas perkrovus servisą; neveiktų su keliomis `assistant` replikomis (šiuo metu – viena, `docker-compose.yml`); nėra kaštų apskaitos (tik skaičius užklausų, ne tokenų/€); IP ateina per `CF-Connecting-IP`/`X-Forwarded-For` (Cloudflare priešakyje – patikrinta `DEPLOY.md`), fingerprint'o nėra.
- **Python ↔ Java ryšys:** `assistant/` niekada tiesiai neskaičiuoja mokesčių ir **neturi savo DB** – jis tik ištraukia duomenis iš teksto (Claude, priverstinis `tool_use`) ir kviečia Java `POST /api/v1/compare` su bendru statiniu API raktu (`ATSIDEK_API_KEY`, Java pusėje tikrinama SHA-256 maiša per `ApiKeyFilter` – vienas raktas visam Python servisui, ne po vartotoją). Tai serveris-serveriui ryšys per Docker tinklą (`http://app:8080`), vartotojo naršyklė `/api/**` niekada nepasiekia tiesiai.
- **DB šiandien – H2 failas, ne tikras PostgreSQL** (`jdbc:h2:file:...;MODE=PostgreSQL`), migracijos – paprastas idempotentinis `schema.sql` (`CREATE TABLE IF NOT EXISTS`), be Flyway/Liquibase.
- **Svarbus priklausomumas:** radau jau **patvirtintą, bet dar neįgyvendintą** planą `docs/specs/taupymo-tikslai/PLAN.md`, kurio F0 fazė numato pilną migraciją H2 → tikras PostgreSQL + Flyway. Ten taip pat parašyta, kad staging/prod Postgres **jau veikia** atskirame LXC (`192.168.68.106:5432`, DB `atsidek_staging`/`atsidek_prod`), ir kad testams naudosim Testcontainers. **Šis monetizacijos planas (D ir dalis A skyriaus) remiasi tuo pačiu Postgres+Flyway pagrindu ir geriausia jį vykdyti po F0 arba su juo kartu** – žr. G skyrių.
- **Vartotojo ID konvencija jau yra**: `eu.atsidek.bank.CurrentUser` grąžina `"google:" + sub` kaip `VARCHAR(64) user_id`, naudojamą visose `bank_*` lentelėse. Naują naudotojo/teisių modelį siūlau tęsti ta pačia konvencija (prefiksuotas tekstinis ID), kad vėliau netektų migruoti ID tipo, jei prisidėtų kitas IdP (pvz. `email:...` magic link'ui).
- **Kainodara kodo/dokumentų lygiu yra nesuderinta**: `SPEC.md` rašo „4,99 €/mėn. arba 39 €/metams“, o šios užduoties tekstas – „19–29 €/metams + vienkartinis sezoninis paketas“. **PRIELAIDA:** naudoju užduotyje duotus skaičius kaip darbinę konfigūracijos reikšmę (pakeičiama bet kada, kaina – config, ne kode), bet tai reikia suderinti – žr. G.
- **CI/CD** (`.github/workflows/ci.yml`): `mvn test` + `pytest` ant `ubuntu-latest` (GitHub-hosted, Docker iš karto – tinka Testcontainers); diegimas – atskiri job'ai tik `self-hosted` runner'yje (`deploy/deploy.sh`), **staging automatiškai po merge į `main`** (`staging.atsidek.eu`), **production tik rankiniu `workflow_dispatch`**. Tai jau atitinka užduoties reikalavimą „pirma testuojama staging".
- **Modelių parinkimas šiandien nėra įgyvendintas:** `extractor.py` visada naudoja vieną modelį iš `AI_MODEL` env (numatyta `claude-haiku-5-5`), nėra sudėtingumo vertinimo ar Sonnet eskalacijos. Kešavimo taip pat nėra.
- **Precedentas limitams iš antro modulio:** `taupymo-tikslai/PLAN.md` jau numato *kitą*, atskirą globalų paros limitą (`GOALS_AI_DAILY_LIMIT`) naujam AI srautui (`/goals/parse`, `/goals/explain`), nesusietą su `assistant`'o `RateLimiter`. **Rekomendacija:** šis monetizacijos planas turėtų **suvienodinti** abu srautus į vieną limitų/apskaitos sistemą (žr. A skyrių), kad nebūtų dviejų nesusijusių ad-hoc stabdžių, kuriuos reikia derinti atskirai.

## A. Naudojimo limitai (prioritetas 1)

### A.1. Kur vykdomas limitas – Java, ne Python, su dviem žingsniais

**Rekomendacija: limitų ir kaštų apskaitos *tiesa* gyvena Java/PostgreSQL pusėje, nes Java jau yra vienintelė vieta su DB prieiga ir vienintelė tiesos versija (šablonas: `bank_*` lentelės, `JdbcTemplate`).** Python (`assistant/`) lieka be savos DB – kaip ir dabar – bet prieš/po kiekvieno Claude kvietimo kreipiasi į naują Java endpoint'ą tuo pačiu `ApiKeyFilter` mechanizmu, kuris jau saugo `/api/v1/compare`:

1. **`POST /api/v1/usage/check`** (prieš Claude kvietimą) – Python siunčia `feature` (`tax_assistant` / `goals_parse` / `goals_explain`), identitetą (žr. A.2) ir gauna atsakymą `allowed: true/false` + priežastį (`ip_limit`, `daily_cap`, `plan_limit`, `paywall_disabled`). Jei `allowed=false` – Claude **nekviečiamas**, vartotojui rodoma žinutė (kaip dabar).
2. **`POST /api/v1/usage/report`** (po Claude kvietimo, su tikrais tokenų skaičiais iš atsakymo) – įrašo eilutę į kaštų žurnalą. Šis žingsnis **fail-open**: jei neprasiskverbia (Java laikinai neprieinamas), vartotojo atsakymas jau pateiktas, apskaita tiesiog praranda vieną eilutę – nelaužom UX dėl apskaitos.

**Kodėl ne viskas Python pusėje (atmesta alternatyva):** reikėtų Python servisui pridėti savo DB ryšį – tai nutoltų nuo esamos architektūros, kurioje Python yra sąmoningai *stateless* (tik ištraukia tekstą, net mokesčių neskaičiuoja). Būtų dvi vietos, kur gyvena vartotojo duomenys (Java Postgres + Python DB), dvi migracijų sistemos. **Kodėl ne viskas Java pusėje, su Python be jokio check'o (atmesta):** Python pats žino tikrą Claude kainą (tokenus) tik po atsakymo – Java to nežino, tad kažkuris servisas visada turi nešti bent dalį apskaitos logikos arčiau Claude kvietimo.

**Kodėl ne atminties ribotuvas visam laikui (dabartinis `limiter.py` liktų, bet nebūtų vienintelis):** jis gerai veikia kaip *pirmas, pigiausias* filtras (žr. A.3 – sluoksniai), bet negali vykdyti „registruotam vartotojui daugiau" taisyklės (neturi identiteto) ir dings perkrovus procesą.

### A.2. Identitetas, kurį Python gauna be papildomo tinklo šuolio per kiekvieną užklausą

Problema: `assistant` neturi prieigos prie Spring Security sesijos (atskiras servisas, atskiras domenas kelio prefiksu). Reikia būdo, kaip Python sužinotų „šis naršyklės vartotojas yra prisijungęs, planas = mokamas" be `check`/`report` endpoint'ų tapimo dar lėtesniais (papildomas Java→Java ratas identitetui).

**Rekomendacija:** kai vartotojas prisijungia (Google OAuth, B skyrius), Java išduoda **pasirašytą, bet ne užšifruotą JWT** (trumpas, 15 min. galiojimas, atnaujinamas kaip sesijos cookie) su `sub` (tas pats `user_id`), `plan`, `exp`. Cookie – `HttpOnly`, `Secure`, `SameSite=Lax`, domeno lygiu `atsidek.eu` (apima ir `assistant`, nes jis tame pačiame apex domene per Caddy/NPM maršrutą). Python **tikrina parašą lokaliai** (bendra paslaptis per env, `ATSIDEK_JWT_SECRET`) – **be tinklo kvietimo**. Jei cookie nėra arba negaliojantis – vartotojas anoniminis (IP-based limitas).

Tai reiškia vienas papildomas tinklo šuolis (`/api/v1/usage/check`) per užklausą, o ne du. `check` endpoint'as gauna `user_id` (iš JWT, jei yra) + IP, ir viename SQL užklausime patikrina abu – vartotojo planą/limitą ir globalų paros biudžetą.

**PRIELAIDA:** Caddy/NPM maršrutizuoja `assistant` tame pačiame apex domene (`atsidek.eu/asistantas`, `/goals/parse` ir pan.), tad cookie keliauja automatiškai. Jei kada nors `assistant` būtų atskirame subdomene, reikėtų `Domain=.atsidek.eu` cookie arba Authorization header persiuntimo iš naršyklės JS – patikrinti prieš implementaciją.

### A.3. Limitų sluoksniai (nuo pigiausio iki griežčiausio)

| Sluoksnis | Kur | Ką stabdo | Pastaba |
| --- | --- | --- | --- |
| 1. Cloudflare Rate Limiting / Turnstile | Cloudflare (edge, DNS priešakyje – jau naudojama pagal `DEPLOY.md`) | Masinį botų srautą, prieš jam pasiekiant serverį | Nereikia naujo kodo, tik Cloudflare konfigūracija. Turnstile (nemokama CAPTCHA alternatyva) – jei anoniminis piktnaudžiavimas taptų problema |
| 2. Esamas `RateLimiter` (Python, atminty, per IP/val.) | `assistant/app/limiter.py` | Vieno IP greitą pliūpsnį | Paliekama kaip yra – pigiausias, jau veikia, be DB kvietimo |
| 3. **Naujas:** `POST /api/v1/usage/check` (Java, Postgres) | Java | Per dieną vienam **vartotojui** (anonimas pagal IP-hash, registruotas pagal `user_id`, mokamas – aukštas „fair use" limitas) | Vienintelė vieta, kuri žino planą |
| 4. Globalus paros biudžeto stabdis | Tas pats `check` endpoint'as, agreguoja `ai_usage_event` | Visą sistemą, nepriklausomai nuo pasiskirstymo tarp IP/vartotojų (apsauga nuo paskirstytos atakos arba nuo netikėtai didelio Claude sąskaitos šuolio) | Konfigūruojamas € arba užklausų skaičius per parą (`AI_DAILY_BUDGET_EUR_CENTS`); viršijus – **visiems** (net mokantiems) rodoma „AI laikinai nepasiekiamas, bandykite rytoj" + skaičiuoklė veikia toliau. Tai sąmoningas verslo sprendimas (žr. G) – ar mokantys turėtų turėti atskirą, aukštesnį avarinį biudžetą |

Numatytieji skaičiai (config, ne kode): anonimas 3/dieną (pagal IP-hash + papildomai minkštas fingerprint, pvz. User-Agent+Accept-Language hash, kad vienas IP su keliais naršyklės skirtukais neapeitų – **ne stiprus fingerprint'as, griežtesnis apribotų privatumą/sukeltų false positives**, žr. G), registruotas nemokamas – pvz. 10/dieną, mokamas – „fair use" (pvz. 200/dieną) pažymėtas kaip minkšta riba su įspėjimu administratoriui, ne kietas blokavimas prie tipinio naudojimo.

### A.4. Kaštų apskaita

Kiekvienas Claude kvietimas (abu srautai – `tax_assistant` ir būsimas `goals_*`) po atsakymo rašo eilutę (per `/api/v1/usage/report`) į `ai_usage_event` (D skyrius): modelis, `input_tokens`, `output_tokens`, apskaičiuota kaina **eurocentais (integer), ne float** – pagal CLAUDE.md pinigų taisyklę. Kainos koeficientai € už 1M tokenų – konfigūracijoje (žr. `claude-api` skill'ą dėl aktualių kainų prieš implementaciją, nes jos keičiasi), ne užkoduotos.

### A.5. Modelių parinkimas ir kešavimas

- **Modelių parinkimas:** šiandien visada Haiku. Pasiūlymas – paprasta euristika Python pusėje (pvz. teksto ilgis + ar aptiktas daugiau nei N skaičių/sąlygų), ne atskiras AI sprendimo kvietimas (tai pats kainuotų). MVP: **palikti Haiku visiems** (ekstrakcija – struktūrizuota užduotis, Haiku su priverstiniu `tool_use` jau veikia gerai pagal esamą kodą) ir modelio eskalaciją įgyvendinti **tik jei realūs duomenys parodys ekstrakcijos klaidų** konkrečiais atvejais (sudėtingi, daugiapajaminiai aprašymai). Nerekomenduoju statyti Sonnet eskalacijos „iš anksto" be duomenų – papildoma sudėtingumo euristika yra savas klaidų šaltinis.
- **Kešavimas:** normalizuoto teksto hash → rezultatas, TTL (pvz. 1 val., nes mokesčių taisyklės/kalkuliatorius nekeičiasi per dieną). Kešas Python atminty (paprasčiausia, veikia su viena replika) arba Postgres lentelė (jei norima išlikti po restart'o ir tarp kelių replikų). **Rekomendacija MVP: atminties LRU su TTL Python pusėje** – tipiniai klausimai ("gavau 2000 eurų, kiek atsidėti") greičiausiai nesikartoja pažodžiui tarp vartotojų (laisvo teksto įvestis), tad kešo efektas realiai bus mažas; neinvestuoti laiko, kol metrika (F.4) parodys, kiek užklausų realiai pasikartoja.

## B. Paskyros

### B.1. Pilna registracija vs magic link vs esamo Google OAuth atvėrimas

| Variantas | Sudėtingumas vienam kūrėjui | Pastabos |
| --- | --- | --- |
| **Google OAuth atvėrimas visiems** (pašalinti/pakeisti `allowed-emails` logiką – naujas „public" režimas, `owner-email` lieka specialus) | **Mažiausias** – Spring Security OAuth2 client jau sukonfigūruotas, testuotas (`AuthTest.java`), veikia produkcijoje bankų moduliui | Reikia atskirti „owner" (banko importas) nuo „bet kuris Google vartotojas" (AI planas) teisių lygiu – šiandien `SecurityConfig` turi vieną `allowed-emails` sąrašą visam kas-ne-public; reikės antro sluoksnio (rolė/entitlement), ne vieno sąrašo |
| **Magic link (el. paštu)** | **Vidutinis** – reikia: tokeno generavimo/galiojimo, laiškų siuntimo infrastruktūros (šiuo metu projekte **nėra** jokio transakcinio laiško siuntimo – `mailbox.org` CLAUDE.md nurodytas kaip asmeninis pašto preferencijas, ne API siuntimui), naujos lentelės, naujo UI srauto | Patrauklu vartotojams be Google paskyros, bet tai **nauja infrastruktūra nuo nulio** |
| **Pilna registracija (el. paštas+slaptažodis)** | **Didžiausias** – slaptažodžių maišos, reset srautas, el. pašto patvirtinimas, brute-force apsauga | CLAUDE.md principas „fail loudly, no swallowed exceptions" + saugumo našta nesverta šiai apimčiai |
| Keycloak | Vidutinis-didelis **infrastruktūros**, mažas kodo | Papildomas servisas, papildoma DB, papildomas atakos plotas – per daug vienam kūrėjui/vienam VPS šiam produkto dydžiui |
| Išorinis IdP (Auth0/Clerk) | Mažas kodo, **bet US cloud** | Prieštarauja CLAUDE.md „European/self-hosted first, avoid adding US cloud services without asking" – nerekomenduoju be atskiro jūsų sutikimo |

**Rekomendacija:** **Etapas 1 – atverti esamą Google OAuth visiems** (nulis naujos autentifikacijos infrastruktūros, tik teisių modelio pakeitimas – žr. žemiau). **Etapas 2 (vėliau, jei reikės) – magic link** kaip alternatyva vartotojams be Google paskyros, kai bus matoma, kiek potencialių mokančių vartotojų tai realiai praranda. Nerekomenduoju Keycloak ar išorinio komercinio IdP šiam produkto dydžiui (keli šimtai/tūkstančiai vartotojų, vienas kūrėjas).

### B.2. Teisių modelio pakeitimas `SecurityConfig`

Šiandien: `/` ir visa viešoji dalis `permitAll()`, visa kita `authenticated()` + email sąraše. Reikia trečios kategorijos: **„prisijungęs, bet ne administratorius"** – gali turėti planą/limitą, bet **neturi** banko importo teisės. Pasiūlymas: `owner-email`/`allowed-emails` lieka tik banko moduliui (role `ADMIN`/`BANK_USER`), naujas atviras `oidcUserService` be email sąrašo apribojimo (bet kuris patvirtintas Google email) suteikia rolę `USER`. `AuthModelAdvice` papildomas – šabloniuose matosi ne tik `loggedIn`, bet ir planas.

## C. Mokėjimai

### C.1. Stripe vs Montonio vs Paysera

| | Stripe | Montonio | Paysera |
| --- | --- | --- | --- |
| Banklinkai LT/LV/EE | Ribotai (kai kurie EU local metodai, ne visi LT bankai) | **Stiprus** – pagrindinė jų niša Baltijos šalims | Stiprus, žinomas LT rinkoje |
| Prenumeratos (recurring) | **Labai geras** – Stripe Billing, pilnas gyvavimo ciklo valdymas (proration, dunning, atšaukimas) | Yra, bet mažiau brandus nei Stripe Billing | Ribotas, daugiau orientuota į vienkartinius mokėjimus |
| Vienkartiniai mokėjimai | Geras (Checkout) | Geras | Geras, populiarus LT el. parduotuvėse |
| Dokumentacija/SDK kūrėjui | **Geriausia rinkoje**, oficialus Java ir Python SDK | Vidutinė, REST API | Vidutinė/senstelėjusi, REST API |
| Webhook'ai | Pilnas įvykių rinkinys, geras pasirašymo/idempotencijos modelis | Yra, mažiau įvykių tipų | Yra, paprastesnis |
| Sandbox | **Test mode** visada iš karto, be atskiro prašymo | Yra test aplinka | Yra test aplinka |
| Mokesčiai (orientacinis) | ~1,5 %+0,25 € EU kortelėms, SEPA pigiau | **PRIELAIDA:** panašūs ar žemesni už banklinkus, patikrinti aktualų tarifyną | **PRIELAIDA:** panašiai, patikrinti aktualų tarifyną |
| Vartotojo pasitikėjimas (LT IV auditorija) | Kortelė/SEPA – įprasta, bet kai kurie LT vartotojai labiau pasitiki banko mokėjimu nei kortele prenumeratai | **Banklinkas = didžiausias pasitikėjimas LT auditorijai** | Banklinkas, taip pat didelis pasitikėjimas |
| Atitiktis CLAUDE.md „European-first" | US kompanija, bet plačiai naudojama ES, palaiko LT kaip verslo šalį | **ES/Baltijos kilmės** | **LT kilmės** |

**Rekomendacija (su išlyga – tai verslo, ne vien techninis sprendimas, žr. G):** pradėti nuo **vieno** tiekėjo paslėpto už plonos sąsajos (`PaymentProvider` interfeisas Java pusėje – `createCheckout`, `handleWebhook`, `cancelSubscription`), kad vėliau pridėti antrą būtų adapterio, ne perrašymo, darbas. Techniškai lengviausia starto taškui – **Stripe** (geriausia dokumentacija, Stripe Billing išsprendžia prenumeratos gyvavimo ciklą „iš dėžės", mažiausiai savo kodo webhook'ų idempotencijai). Rinkos/pasitikėjimo požiūriu LT IV auditorijai – **Montonio** gali konvertuoti geriau dėl banklinko. **Tai sprendimas, kurio pats negaliu priimti be jūsų – žr. G.1.**

### C.2. Produktai ir kainos

Konfigūracijoje (ne kode), pvz. `application.yml` + env override arba `app_setting` DB lentelė (B.2/F.3):

- `annual_subscription` – metinė prenumerata (galiojimo pabaiga = pirkimo data + 1 metai arba fiksuota kalendorinė data, pvz. 365 d.)
- `seasonal_pack` – vienkartinis sezoninis paketas (pvz. galioja nuo pirkimo iki balandžio 2 d., GPM deklaravimo terminas – **PRIELAIDA:** tikslią datą/logiką reikia patvirtinti su jumis)

### C.3. Teisių (entitlements) modelis

`entitlement(user_id, product_code, expires_at, source, created_at)` – viena eilutė = viena aktyvi teisė. `check`/`report` endpoint'ai skaito **tik** šią lentelę (ne `subscription`/`payment` tiesiai), kad limitų logika nesusietų su konkrečiu mokėjimo tiekėju. Webhook handler'is **rašo** į `entitlement` po sėkmingo mokėjimo/prenumeratos atnaujinimo ir **trina/riboja** `expires_at` po atšaukimo/nesėkmės – žr. D skyrių dėl lentelių.

### C.4. Webhook'ų idempotentiškumas, nesėkmės, atšaukimai

- Kiekvienas gautas webhook event'as – pirma `INSERT` į `payment_webhook_event` su **unikaliu** `(provider, external_event_id)` apribojimu; jei jau egzistuoja – grąžinti 200 ir nieko nedaryti (tiekėjas kartoja siuntimą, tai normalu).
- Parašo (signature) tikrinimas **prieš** bet kokį JSON parse'inimą, naudojant tiekėjo SDK/dokumentuotą algoritmą, raktas – tik env.
- Nesėkmingas/grąžintas (refund) mokėjimas → `entitlement.expires_at` nekeičiamas atgal automatiškai be aiškios taisyklės; **PRIELAIDA:** MVP – refund = rankinis administratoriaus veiksmas (per DB arba paprastą admin endpoint'ą), nes refund dažnis bus žemas šiam produkto dydžiui ir automatinio „atimti teisę po grąžinimo" logika turi savų kraštinių atvejų (dalinis refund, ginčas).
- Atšaukimas (cancel) prenumeratos → `subscription.status='canceled'`, `entitlement.expires_at` **nekeičiamas** iki einamo periodo galo (vartotojas jau sumokėjo už tą periodą) – standartinis SaaS elgesys.

## D. DB schema (Flyway eskizas)

**Priklauso nuo F0** (`taupymo-tikslai/PLAN.md`) – tikro PostgreSQL + Flyway. Jei F0 dar nemerge'intas, kai imamasi šio darbo, šios migracijos pridedamos **po** F0 migracijų numeracijoje (nenumeruoju konkrečiu `V__` numeriu, nes jis priklauso nuo to, kiek migracijų F0/F1/F2 jau užėmė).

```sql
-- app_user: naudotojo profilis. id ta pati konvencija kaip bank_* (CurrentUser: "google:<sub>").
CREATE TABLE app_user (
    id VARCHAR(64) PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

-- entitlement: viena eilutė = viena aktyvi teisė; check/report skaito TIK šitą lentelę.
CREATE TABLE entitlement (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL REFERENCES app_user(id),
    product_code VARCHAR(40) NOT NULL,       -- 'annual_subscription' | 'seasonal_pack'
    expires_at TIMESTAMP NOT NULL,
    source VARCHAR(20) NOT NULL,             -- 'stripe' | 'montonio' | 'manual'
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX ix_entitlement_user ON entitlement (user_id, expires_at);

-- subscription: prenumeratos gyvavimo ciklas (tik recurring produktams).
CREATE TABLE subscription (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL REFERENCES app_user(id),
    product_code VARCHAR(40) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    external_subscription_id VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,             -- 'active' | 'canceled' | 'past_due'
    current_period_end TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    canceled_at TIMESTAMP,
    UNIQUE (provider, external_subscription_id)
);

-- payment: mokėjimų žurnalas (audit/apskaita, ne teisių šaltinis).
CREATE TABLE payment (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL REFERENCES app_user(id),
    subscription_id BIGINT REFERENCES subscription(id),
    provider VARCHAR(20) NOT NULL,
    external_payment_id VARCHAR(100) NOT NULL,
    amount_cents INT NOT NULL,               -- BigDecimal/centai, ne float
    currency VARCHAR(3) NOT NULL DEFAULT 'EUR',
    status VARCHAR(20) NOT NULL,             -- 'succeeded' | 'failed' | 'refunded'
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (provider, external_payment_id)
);

-- payment_webhook_event: idempotentiškumas.
CREATE TABLE payment_webhook_event (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    provider VARCHAR(20) NOT NULL,
    external_event_id VARCHAR(100) NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT now(),
    processed_at TIMESTAMP,
    payload JSONB NOT NULL,
    UNIQUE (provider, external_event_id)
);

-- ai_usage_event: kaštų žurnalas, abiems AI srautams (tax_assistant, goals_parse, goals_explain).
CREATE TABLE ai_usage_event (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id VARCHAR(64) REFERENCES app_user(id),   -- NULL = anonimas
    anon_key VARCHAR(64),                            -- IP/fingerprint hash, kai user_id NULL
    feature VARCHAR(30) NOT NULL,
    model VARCHAR(40) NOT NULL,
    input_tokens INT NOT NULL,
    output_tokens INT NOT NULL,
    cost_eur_cents INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
CREATE INDEX ix_usage_user_date ON ai_usage_event (user_id, created_at);
CREATE INDEX ix_usage_date ON ai_usage_event (created_at);

-- app_setting: globalūs feature flag'ai (paywall įjungimas/išjungimas be deploy; ta pati idėja kaip esamas user_setting).
CREATE TABLE app_setting (
    setting_key VARCHAR(40) PRIMARY KEY,
    setting_value VARCHAR(200) NOT NULL
);
```

## E. Teisiniai / apskaitos klausimai (tik pažymėta, nespręsta)

- Sąskaitų faktūrų išrašymas pirkėjams – ar naudoti tiekėjo (Stripe Invoicing / Montonio) įmontuotą funkciją, ar atskirą LT apskaitos programą.
- PVM registracijos riba Lietuvoje – **PRIELAIDA:** apie 45 000 € metinės apyvartos (patikrinti aktualią VMI ribą prieš bet kokį verslo sprendimą, nesu tikras dėl dabartinio dydžio).
- BDAR: kas saugoma apie vartotoją (el. paštas, Google `sub`, mokėjimų žurnalas, AI užklausų tekstas – **šiuo metu tekstas NEsaugomas**, pagal README; jei pridėsime kaštų žurnalą, jis saugo tik tokenų skaičius/kainą, ne tekstą – patvirtinti, kad taip ir norime), kiek laiko, ar reikia duomenų trynimo srauto (vartotojo prašymas pašalinti paskyrą).
- Privatumo politikos (`privatumas.html`) ir taisyklių atnaujinimas – nauja duomenų tvarkymo šalis (mokėjimo tiekėjas kaip duomenų tvarkytojas), prenumeratos sąlygos, atšaukimo teisė (14 d. per nuotolį sudarytoms sutartims – skaitmeninio turinio/paslaugos atšaukimo teisės išimtys LT/ES vartotojų teisėje).
- Ar reikalingas duomenų tvarkymo susitarimas (DPA) su pasirinktu mokėjimo tiekėju.

## F. Diegimas

### F.1. Etapai (MVP pirmas: limitai + vienas mokėjimo būdas)

1. **F0 (jei dar ne merge'intas) – PostgreSQL + Flyway**, pagal `taupymo-tikslai/PLAN.md` – prerekvizitas D skyriui. Jei šis darbas startuoja pirmiau nei F0, D skyriaus migracijos vis tiek rašomos Flyway formatu, tiesiog laukia F0 merge'o kaip bazės.
2. **MVP-1 – limitai be mokėjimų.** `ai_usage_event`, `app_setting` (paywall flag – kol nėra mokėjimų, flag tiesiog nenaudojamas, bet lentelė/mechanizmas paruoštas), `/api/v1/usage/check` + `/report` (kol nėra paskyrų – tik anoniminė dalis: IP-hash limitas + globalus paros biudžetas), **suvienodinti** `assistant` ir būsimą `goals_*` srautą per šį vieną endpoint'ą (vietoj dviejų atskirų ad-hoc limiterių). Šis žingsnis vienas pats jau sumažina Claude kaštų riziką – **gali ir turėtų būti padarytas net jei mokėjimai atidėliojami**.
3. **MVP-2 – paskyros.** `app_user`, Google OAuth atvėrimas visiems (B.2), JWT cookie identitetui (A.2), registruotam vartotojui aukštesnis nemokamas limitas.
4. **MVP-3 – mokėjimai.** `entitlement`, `subscription`, `payment`, `payment_webhook_event`, vieno tiekėjo integracija (checkout + webhook), `app_setting.paywall_enabled` tikrai naudojamas – mokamas „fair use" limitas `check` endpoint'e.
5. **Vėliau** – antras mokėjimo būdas, admin stebėsenos puslapis, kešavimas (jei metrika rodo prasmę), modelio eskalacija (jei duomenys rodo poreikį).

### F.2. Feature flag paywall'ui

`app_setting` lentelės raktas `paywall_enabled` (`true`/`false`), skaitomas `check` endpoint'e per trumpos TTL (≈30 s) atminties kešą (pvz. Caffeine arba paprastas `AtomicReference` + laiko žyma Java servise) – pakeitimas DB įsigalioja per ≤30 s, **be redeploy**. Kai `false` – visi AI kvietimai leidžiami pagal registruoto vartotojo limitą, nepaisant plano (naudinga paleidimui/demonstracijai arba avariniam atšaukimui, jei mokėjimų integracija sulūžtų).

### F.3. Testavimo strategija

- **Java vienetiniai testai:** limitų/teisių logika (grynos funkcijos, be DB) – kraštiniai atvejai (lygiai limitas, galiojimas baigiasi tiksliai šiandien, paywall išjungtas).
- **Java integraciniai (Testcontainers Postgres,** pagal F0 nusistovėjusį šabloną**):** `check`/`report` endpoint'ai su realia DB – lenktynių sąlygos (du vienalaikiai `check` tam pačiam vartotojui ribos pakraštyje), webhook idempotentiškumas (siųsti tą pačią `external_event_id` du kartus, patikrinti vieną eilutę).
- **Python testai (`pytest`, esamas `Protocol`/fake šablonas** kaip `Calculator`/`Extractor`**):** naujas `UsageClient` protokolas su fake implementacija testams – patikrinti, kad `report` nesėkmė nenutraukia vartotojo atsakymo (fail-open), kad `check=false` neleidžia Claude kvietimo.
- **Webhook'ai su sandbox:** atskiras, **rankiniu `workflow_dispatch` paleidžiamas** CI job'as (ne kiekvienam PR – sutampa su esamu `taupymo-tikslai` sprendimu dėl pilnų AI eval'ų), naudojantis tiekėjo test-mode raktus iš GitHub Environment secrets, niekada repo. Kiekvienam PR – tik fake/mock webhook payload'ai, be tikro tinklo kvietimo.
- **Jokių tikrų mokėjimo raktų** niekur CI/dev aplinkoje – tik `sandbox`/`test` raktai.

### F.4. Stebėsena

- AI kaštai per dieną: `SELECT sum(cost_eur_cents) ... GROUP BY date` iš `ai_usage_event` – paprastas admin-only HTML puslapis (ta pati `owner-email` apsauga, kuri jau yra banko moduliui – nereikia naujos infrastruktūros, jokio Grafana/Prometheus MVP etape).
- Konversija: registruotų vartotojų skaičius, kiek jų turi aktyvų `entitlement`.
- Limitų pasiekimai: kiek `check` atsakymų per dieną buvo `allowed=false` ir dėl kokios priežasties (grupuoti pagal `ip_limit`/`daily_cap`/`plan_limit`) – rodo, ar laisvas limitas per griežtas/per laisvas.

## G. Atviri klausimai man (sprendimai, kurių planas pats nepriima)

1. **Mokėjimo tiekėjas:** Stripe (geriausia DX/Billing) ar Montonio (geresnis LT banklink pasitikėjimas) kaip pirmas/vienintelis MVP tiekėjas? Tai rinkos sprendimas, ne techninis.
2. **Kaina:** `SPEC.md` rašo 4,99 €/mėn. arba 39 €/metams, užduotis – 19–29 €/metams. Kurie skaičiai galutiniai?
3. **Sezoninio paketo logika:** nuo kada iki kada jis galioja (fiksuota kalendorinė data prieš GPM deklaravimą, ar N dienų nuo pirkimo)?
4. **Ar atverti Google OAuth visiems dabar**, ar palaukti, kol bus mokėjimai (MVP-2 prieš MVP-3, ar kartu)?
5. **Globalus paros biudžeto stabdis mokantiems vartotojams:** kai pasiekiamas bendras paros € biudžetas, ar **mokantys** vartotojai taip pat laikinai atkertami nuo AI (paprasčiau, pigiau), ar jiems reikia atskiro, aukštesnio avarinio biudžeto (sudėtingiau, bet apsaugo mokamą produktą nuo nemokamo srauto)?
6. **Anoniminio fingerprint'o griežtumas:** vien IP, ar IP+User-Agent/Accept-Language hash? Griežtesnis mažiau apeinamas, bet didesnė false-positive (keli vartotojai už vieno NAT/įmonės IP) rizika.
7. **Refund/atšaukimo automatika:** priimtina, kad MVP refund'ai tvarkomi rankiniu administratoriaus veiksmu, ar reikia automatinio `entitlement` atšaukimo iškart po webhook'o?
8. **VAT/sąskaitos:** naudoti tiekėjo įmontuotą sąskaitų funkciją, ar jūs tvarkote per savo apskaitos programą atskirai?
9. **Ar reikia magic link dabar**, ar Google-only pakanka pirmam etapui (dalis vartotojų neturi/nenaudoja Google paskyros darbui)?
10. **`assistant` replikų skalė:** ar numatote kada daugiau nei vieną `assistant` instanciją (affectuoja, ar atminties lygio `RateLimiter`/kešas liks pakankami, ar reikės Redis)?
