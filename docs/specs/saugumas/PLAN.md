# Saugumas ir asmens duomenų apsauga (atskiras inkrementas, atskiras deploy)

## Tikslas
Atsidėk tampa daugianaudotojiška aplikacija. Kiekvieno naudotojo duomenys
(bank operacijos, taupymo tikslai, el. paštas) turi būti prieinami TIK jam,
saugomi minimaliai, ištrinami pilnai, ir atitikti BDAR.

## Prieš planuojant: auditas (pirmas darbas, be kodo pakeitimų)
- Duomenų žemėlapis: kokie asmens duomenys saugomi, kurioje lentelėje/faile/loguose,
  kas juos pasiekia, kiek laiko saugomi, ar patenka į backup'us ir į Claude API.
- Visų endpoint'ų sąrašas su dabartine prieigos kontrole.
- Šis auditas bus ir privatumo politikos pagrindas.

## P0 – naudotojų duomenų izoliacija
- Kiekviena lentelė su naudotojo duomenimis: owner_id UUID NOT NULL,
  FK į app_user ON DELETE CASCADE.
- Repository sluoksnis: jokių findById be savininko; kiekvienas užklausimas
  filtruoja pagal owner_id iš saugumo konteksto, ne iš užklausos parametrų.
- Svetimas resursas -> 404 (ne 403, kad neatskleistų egzistavimo).
- IDOR testų rinkinys: du naudotojai; B bando kiekvieną endpoint'ą su A ID.
- Įvertinti PostgreSQL Row-Level Security kaip antrą gynybos sluoksnį.

## P0 – bank modulio perėjimas į daugianaudotojišką
- Esami bank duomenys vienkartine Flyway migracija priskiriami admin naudotojui;
  LegacyDataAdopter po to pašalinamas (jokio runtime "įsivaikinimo").
- Bank modulis atveriamas ROLE_USER tik po IDOR testų ir izoliacijos.
- Importo failai: dydžio ir tipo ribos, saugus parsinimas, žalias failas
  ištrinamas po importo.
- ROLE_ADMIN ir owner "įsivaikinimo" (adoption) logika siejama su
  `provider_subject`, ne su el. paštu (el. paštas gali keistis/būti perrašytas
  Google pusėje; `provider_subject` – ne). Taikoma, kai šis modulis migruojamas;
  taupymo-tikslų inkrementas (`docs/specs/taupymo-tikslai-atskyrimas.plan.md`)
  šios logikos nekeičia, tik dokumentuoja dabartinę (el. paštu paremtą) elgsena.

## P1 – duomenų apsauga
- TLS visur; DB pasiekiama tik iš aplikacijos LXC (jau yra – patikrinti).
- DB rolės: atskiri naudotojai staging/prod; aplikacijos naudotojas nėra schemos
  savininkas; Flyway migracijoms atskiras naudotojas.
- Jautriems laukams (IBAN, sąskaitų numeriai, kontrahentų pavadinimai) įvertinti
  stulpelių šifravimą su raktu iš env/secret store.
- Backup'ai šifruoti; restore testas; aprašyti, kaip trynimas veikia backup'ams
  (saugojimo terminas).
- Logai: jokių operacijų, sumų, el. paštų ar tokenų; PII maskavimas;
  logų saugojimo terminas.

## P1 – web saugumas
- Sesijos slapukai: Secure, HttpOnly, SameSite; sesijos timeout.
- CSRF visoms būsenos keitimo užklausoms.
- Antraštės: CSP, HSTS, X-Content-Type-Options, frame-ancestors, Referrer-Policy.
- Rate limit autentifikacijos ir brangiems endpoint'ams.

## P1 – AI ir trečiosios šalys
- Į Claude API nesiunčiami bank duomenys ar identifikuojantys duomenys,
  jei to nereikia užduočiai; aprašyti, kas siunčiama.
- Trečiųjų šalių sąrašas (Google OAuth, Anthropic, hostingas) privatumo politikai.

## P1 – BDAR procesai
- Sprendimas, kas nutinka išsaugotiems duomenims, kai naudotojas netenka teisės
  (pvz. beta režimas baigiasi konkrečiam feature'ui): bent peržiūra ir duomenų
  eksportas lieka prieinami, net kai patį feature'ą naudoti (kurti/keisti) jau
  negalima. Taikoma visiems premium feature'ams, ne tik taupymo tikslams.
- Teisė susipažinti: naudotojo duomenų eksportas (JSON) iš /paskyra.
- Teisė ištrinti: pilnas trynimas (jau yra pagrindas per CASCADE) + testas,
  kad po trynimo neliko jokių naudotojo eilučių jokioje lentelėje.
- Duomenų tvarkymo veiklos įrašai (records of processing).
- Pažeidimo procedūra: kaip aptikti, kas daroma, pranešimas VDAI per 72 val.

## P2 – priklausomybės, paslaptys, stebėsena
- Dependabot / OWASP dependency-check CI; reguliarūs bazinių image atnaujinimai.
- Paslapčių rotacija (taip pat anksčiau pokalbiuose atskleistų slaptažodžių).
- Stebėsena: nesėkmingi prisijungimai, 403/404 šuoliai, neįprastas eksportas –
  Prometheus/Grafana alertai.

## Darbo tvarka
- Pirmiausia auditas ir detalus planas su ADR; atviri klausimai man prieš kodą.
- P0 – atskiras PR ir atskiras deploy į staging, tik po to produkcija.
- Jokio push ar produkcijos deploy be mano leidimo.
