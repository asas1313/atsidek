# Atsidėk: IV mokesčių planavimo įrankio MVP specifikacija

Oct 23, 2026 · @Andrius

## Pavadinimas

Produktas vadinasi **Atsidėk**, pagrindinis domenas atsidek.lt. Pavadinimas yra pats produkto pažadas: kas mėnesį pasakyti, kiek atsidėti mokesčiams. Papildomas SEO domenas kiekatideti.lt nukreipia į nemokamą skaičiuoklę.

Šūkio variantas: „Atsidėk ir pamiršk balandžio stresą.“ Projekto katalogas: `iv-mokesciu-skaiciuokle`.

## Problema ir pozicionavimas

Produktas atsako į vieną klausimą: kiek pinigų turiu atsidėti šį mėnesį ir kokia bus mano metinė deklaracija. Tai planavimo įrankis, ne apskaitos programa.

Apskaitą jau nemokamai daro VMI i.APS, o sąskaitų išrašymą – dešimtys programų. Niekas nežiūri į priekį: po 2026 m. reformos IV pajamos sumuojamos su darbo, nuomos ir kitomis pajamomis, o GPM tarifas progresinis. Žmogus su mišriomis pajamomis nebegali pats įvertinti, kiek skolės baląndį.

Vertės pažadas: „Niekada nebežinosi, kiek reikės sumokėti baląndį. Kas mėnesį matai tikslią sumą, kurią reikia atsidėti.“

Ko produktas nedaro: neišrašo sąskaitų, nepateikia deklaracijų VMI, nemoka mokesčių už vartotoją ir neveda oficialaus pajamų žurnalo.

## Tikslinė auditorija ir kainodara

Pirmasis segmentas yra IV pagal pažymą vykdantys žmonės su mišriomis pajamomis ir metinėmis IV pajamomis nuo maždaug 15 000 €. Jiems planavimo klaida kainuoja šimtus ar tūkstančius eurų, todėl jie linkę mokėti.

| Segmentas | Pavyzdys | Kodėl jiems skauda | Prioritetas |
| --- | --- | --- | --- |
| IT kontraktoriai ir konsultantai | B2B programuotojas, IV + darbo sutartis | Didelės pajamos, progresinis GPM, VSD lubos | Pirmas |
| Kūrybininkai ir laisvosios profesijos | Dizaineris, vertėjas, fotografas | Nereguliarios pajamos, nežino, kiek atsidėti | Pirmas |
| IV + nuomos pajamos | Nuomoja butą ir dirba savarankiškai | Pajamos sumuojamos, sudėtinga prognozė | Antras |
| Pradedantys IV | Pirmi metai, nesupranta sistemos | Daug klausimų, mažas mokėjimo noras | Nemokamas srautas |

Verslo liudijimų turėtojai į pirmą versiją neįtraukiami: jų apmokestinimas fiksuotas ir planuoti mažai ko reikia.

Kainodara: nemokama skaičiuoklė be registracijos, mokamas planas 4,99 €/mėn. arba 39 €/metams. Metinis planas skatinamas, nes pagrindinė vertė susikaupia prieš deklaravimą. Kaina parenkama taip, kad būtų pigesnė už vieną buhalterio konsultaciją per metus.

## Mokesčių skaičiavimo logika (2026 m.)

Skaičiavimo variklis yra produkto širdis, todėl jis kuriamas kaip atskiras, versijuojamas taisyklių modulis: kiekviena metų taisyklių versija turi savo parametrus ir testų rinkinį. Žemiau pateikti parametrai surinkti iš viešų šaltinių; pažymėti punktai turi būti patikrinti pagal GPM įstatymo tekstą ir VMI skaičiuoklę prieš paleidimą.

### Bazė

Apmokestinamosios IV pajamos = pajamos − išlaidos. Išlaidos skaičiuojamos arba pagal faktines išlaidas, arba taikant 30 % išlaidų metodą ([Triniti](https://triniti.eu/wp-content/uploads/2025/09/Mokesciu_reforma_suvestine_2025-07-01.pdf)). Vartotojas pasirenka metodą, o įrankis parodo, kuris naudingesnis.

### GPM nuo IV pajamų

| Metinės apmokestinamosios IV pajamos | Efektyvus GPM tarifas | Pastaba |
| --- | --- | --- |
| iki 20 000 € | 5 % | Taikomas mokesčio kreditas |
| 20 000–35 000 € | 5–15 %, didėja tolygiai | Mokesčio kredito formulė |
| 35 000–42 500 € | 15–20 %, didėja tolygiai | Nauja nuo 2026 m., formulę patikrinti įstatyme |
| virš 42 500 € | 20 / 25 / 32 % | Progresiniai tarifai sumuojant su kitomis pajamomis |

Progresiniai tarifai sumuojamoms pajamoms: 20 % iki 36 VDU, 25 % nuo 36 iki 60 VDU, 32 % virš 60 VDU; jie taikomi sumuojant darbo, IV, nuomos ir kitas pajamas ([Triniti](https://triniti.eu/wp-content/uploads/2025/09/Mokesciu_reforma_suvestine_2025-07-01.pdf)). Šaltinyje ribos apskaičiuotos pagal 2025 m. VDU; 2026 m. jos perskaičiuojamos pagal 2026 m. VDU.

### Sodros įmokos (VSD ir PSD)

IV vykdantys asmenys moka 19,5 % įmokų (VSD 12,52 % + PSD 6,98 %) nuo 90 % apmokestinamųjų pajamų; pensijų kaupimo dalyviams pridedami 3 %. 2026 m. VDU yra 2 312,15 €, todėl įmokų lubos siekia 43 VDU = 99 422,45 €. Minimali mėnesio PSD įmoka yra 80,48 € (6,98 % nuo 1 153 € MMA) ([Sodra](https://sodra.lt/wp-content/uploads/2026/01/Savarankiskai_dirbantiems_asmenims_2026-1.pdf)).

```latex
\text{Įmokos} = \min(0{,}9 \cdot P_{apm},\ 43 \cdot VDU) \cdot (0{,}195 + 0{,}03 \cdot k)
```

Čia P\_apm yra apmokestinamosios IV pajamos, o k = 1, jei vartotojas dalyvauja II pakopos pensijų kaupime, kitu atveju 0.

### Mėnesio rekomendacija

Kiekvieną mėnesį variklis ekstrapoliuoja metines pajamas (faktas iki šiol + vartotojo prognozė likusiems mėnesiams), apskaičiuoja metinę GPM ir Sodros sumą ir parodo, kiek reikia atsidėti, kad iki gegužės 1 d. būtų sukaupta visa suma.

### Patikrinti prieš paleidimą

- [ ] Tiksli mokesčio kredito formulė intervalui 35 000–42 500 €
- [ ] Ar sumokėtos VSD ir PSD įmokos 2026 m. dar atimamos iš apmokestinamųjų pajamų
- [ ] Kaip tiksliai IV dalis virš 42 500 € sumuojama su darbo pajamomis taikant 36 ir 60 VDU ribas
- [ ] Pirmųjų veiklos metų įmokų lengvata ir pensininkų išimtys
- [ ] 10 testinių scenarijų, sutikrintų su VMI ir Sodros skaičiuoklėmis

## MVP apimtis

Pirmoji versija daro tik du dalykus gerai: tiksliai suskaičiuoja metinius mokesčius ir kas mėnesį pasako, kiek atsidėti. Viskas kita atidedama, kol nemokamas srautas parodys paklausą.

| Funkcija | Planas | Versija |
| --- | --- | --- |
| Metinė IV mokesčių skaičiuoklė su mišriomis pajamomis (darbas, nuoma) | Nemokama, be registracijos | MVP |
| Rezultato paaiškinimas: kiek GPM, kiek VSD, kiek PSD ir kodėl | Nemokama | MVP |
| 30 % ir faktinių išlaidų palyginimas | Nemokama | MVP |
| El. pašto forma „pranešti, kai bus mėnesinis planavimas“ | Nemokama | MVP |
| Paskyra ir rankinis mėnesio pajamų įvedimas | Mokama | MVP |
| Mėnesio rekomendacija „atsidėk X €“ ir metų prognozė | Mokama | MVP |
| Priminimas el. paštu kas mėnesį | Mokama | MVP |
| Banko išrašo importas (CSV, camt.053) su IV pajamų pažymėjimu | Mokama | v2 |
| Scenarijai („kas, jei nuo kovo pradėsiu dirbti pagal darbo sutartį“) | Mokama | v2 |
| Eksportas i.APS įvedimui | Mokama | v2 |
| Automatinis banko duomenų gavimas per atvirąją bankininkystę | Mokama | v3, žr. skyrių apie licencijavimą |
| PVM mokėtojų režimas, verslo liudijimai | Atidėta | Vėliau arba niekada |

## Ekranai ir vartotojo kelias

Vartotojas pirmą vertę gauna per 60 sekundžių be registracijos, o registracija prašoma tik tada, kai jis nori sekti metus.

1. **Skaičiuoklė (pagrindinis puslapis).** Vienas ekranas: numatomos metinės IV pajamos, išlaidų metodas, kitos pajamos (darbo užmokestis, nuoma), II pakopos kaupimas taip/ne. Rezultatas atsinaujina iš karto.
2. **Rezultatas.** Didelis skaičius „Iš viso mokesčių: X €“, išskaidymas į GPM, VSD ir PSD, efektyvus tarifas ir suma, kurią reikėtų atsidėti kas mėnesį. Žemiau trumpas paaiškinimas paprasta kalba.
3. **Kvietimas.** „Nori, kad tai būtų skaičiuojama automatiškai pagal tikras tavo pajamas kas mėnesį?“ su el. pašto lauku arba registracija.
4. **Mėnesio suvestinė (mokama).** Šių metų pajamos iki šiol, prognozė metams, jau atidėta suma, šio mėnesio rekomendacija ir atsilikimas ar perteklius.
5. **Pajamų įvedimas (mokama).** Sąrašas pagal mėnesius: data, suma, klientas. Greitas įvedimas iš telefono.
6. **Nustatymai.** Išlaidų metodas, II pakopa, kitų pajamų prognozė, priminimų diena.

Visa sąsaja lietuvių kalba; anglų kalba pridedama vėliau užsieniečiams, vykdantiems IV Lietuvoje.

## Techninė architektūra

Vienas Spring Boot monolitas su atskiru, nuo nieko nepriklausančiu skaičiavimo moduliu. Tai pigiausia ir greičiausia pastatyti vienam žmogui, o vėliau nesunku išskaidyti.

| Komponentas | Sprendimas | Kodėl |
| --- | --- | --- |
| Skaičiavimo variklis | Grynas Java 21 modulis, `TaxRules2026` kaip versijuojami parametrai | Lengva testuoti, kasmet pridedama nauja versija |
| Backend | Spring Boot 3 | Tavo kasdienis įrankis |
| Frontend | Thymeleaf + htmx | Serverio generuojami puslapiai gerai indeksuojami Google, nereikia atskiro SPA |
| Duomenų bazė | PostgreSQL | Paprasta, patikima |
| Prisijungimas | Magic link el. paštu | Nereikia slaptažodžių |
| Mokėjimai | Stripe Billing arba Paddle | Paddle veikia kaip merchant of record ir sutvarko ES PVM už skaitmenines paslaugas |
| El. laiškai | Postmark arba Brevo | Priminimai ir prisijungimo nuorodos |
| Talpinimas | Mažas VPS ES (pvz., Hetzner), Docker | Kelių eurų per mėnesį kaina, duomenys ES |
| Stebėsena | Plausible analitika, Uptime Kuma | Privatumui draugiška, be slapukų sutikimo |

### Duomenų modelis

- `User`: el. paštas, plano būsena, sukūrimo data.
- `TaxProfile` (vienas metams): metai, išlaidų metodas, II pakopa, kitų pajamų prognozė.
- `IncomeEntry`: data, suma, klientas, šaltinis (rankinis arba importas).
- `SetAsideEntry`: kiek ir kada vartotojas atsidėjo.
- `RuleSet`: taisyklių versija, pagal kurią atliktas skaičiavimas, kad būtų galima paaiškinti senus rezultatus.

Finansiniai duomenys saugomi ES, šifruojami ramybės būsenoje, o vartotojas gali bet kada eksportuoti ir ištrinti savo duomenis (BDAR).

## Banko duomenys ir licencijavimas

Smulkiojo verslininko sąskaitos tiesiogiai atkartoti negalima, bet didžiąją jos naudos dalį vartotojui galima suteikti be jokios licencijos. Skirtumas tarp to, ką daro bankas, ir ką gali daryti įrankis:

| SVS funkcija | Ar gali įrankis | Kaip |
| --- | --- | --- |
| Automatiškai apskaičiuoti mokesčius | Taip | Tai ir yra produkto branduolys |
| Pasakyti, kiek atsidėti | Taip, net geriau | SVS nemato darbo ir nuomos pajamų, įrankis mato |
| Matyti įplaukas banke | Taip, bet per tarpininką | Atvirosios bankininkystės (AIS) paslauga; reikia Lietuvos banko leidimo arba licencijuoto tarpininko |
| Pačiam nuskaičiuoti ir pervesti mokesčius | Ne | Tai daro tik finansų įstaiga; pinigų valdymas reikalautų licencijos |
| Perduoti duomenis VMI ir Sodrai | Ne | Tai valstybės ir bankų susitarimo dalis |
| Inicijuoti mokėjimą į VMI ar Sodrą | Iš dalies | Paprasčiausia: paruošti mokėjimo rekvizitus (įmokos kodas, suma), kuriuos vartotojas patvirtina savo banke |

Praktinis kelias: MVP ir v2 versijose banko duomenys tik importuojami iš failo (CSV, camt.053), licencijos nereikia. v3 versijoje automatinis gavimas per licencijuotą atvirosios bankininkystės tarpininką (pvz., Enable Banking ar Salt Edge), kuris turi PSD2 leidimą; jų sąlygas ir kainas reikia patikrinti. Savo AIS leidimo siekti neverta, kol nėra bent kelių šimtų mokančių vartotojų.

SVS rizika produktui: kol kas ją siūlo vienas bankas, bet schema sukurta taip, kad galėtų prisijungti ir kiti. Jei prisijungs didieji bankai, paprasto IV vykdytojo, turinčio tik IV pajamas, vertė sumažės. Mišrių pajamų planavimas išliks įrankio pranašumu.

## Validavimas ir paleidimo planas

Paklausa tikrinama be jokio tiesioginio kontaktavimo: nemokama skaičiuoklė pritraukia lankytojus iš paieškos, o el. pašto registracijos parodo, ar žmonės nori mokamos dalies. Sėkmės riba: 300 el. pašto adresų per 3 mėnesius nuo skaičiuoklės paleidimo.

| Etapas | Trukmė | Rezultatas | Sprendimo riba |
| --- | --- | --- | --- |
| 1. Skaičiavimo variklis ir testai | 2 savaitės | 10 scenarijų sutampa su VMI ir Sodros skaičiuoklėmis | Visi testai praeina |
| 2. Nemokama skaičiuoklė + 3 SEO straipsniai | 2 savaitės | Viešas puslapis su el. pašto forma | Puslapis indeksuotas |
| 3. Stebėjimas | 2–3 mėnesiai | Lankomumas, registracijos | 300 adresų: tęsti; mažiau nei 100: keisti pozicionavimą arba sustoti |
| 4. Mokama dalis | 4 savaitės | Paskyros, mėnesio suvestinė, Stripe/Paddle | Paleisti iki vasario, prieš deklaravimo sezoną |
| 5. Deklaravimo sezonas | Kovas–gegužė | Pirmieji mokantys vartotojai | 50 mokančių iki gegužės |

Laikas paleidimui palankus: skaičiuoklė iki sausio dar spėja surinkti paieškos srautą, o 2026 m. pajamų deklaravimas (2027 m. pavasarį) bus pirmasis pagal naujas taisykles, kai daug žmonių pirmą kartą susidurs su sumuojamomis pajamomis.

Turinys SEO: „IV mokesčiai 2026 skaičiuoklė“, „kiek atsidėti mokesčiams individuali veikla“, „IV ir darbo sutartis mokesčiai 2026“, „30 % išlaidos ar faktinės“. Kiekvienas straipsnis baigiasi nuoroda į skaičiuoklę.

## Rizikos ir atviri klausimai

Didžiausia rizika yra ne techninė, o rinkos: mažas Lietuvos vartotojų noras mokėti už įrankius ir valstybės automatizacija.

| Rizika | Tikimybė | Poveikis | Atsakas |
| --- | --- | --- | --- |
| Mažas noras mokėti | Didelė | Lėtas pajamų augimas | Metinis planas, aiški vertė prieš deklaravimą, validavimas prieš kuriant mokamą dalį |
| Didieji bankai prisijungia prie SVS | Vidutinė | Mažėja vertė vien IV turintiems | Fokusas į mišrias pajamas ir scenarijus |
| VMI patobulina i.APS planavimo funkcijomis | Maža | Konkurentas nemokamai | Greitis, paprastumas, mišrių pajamų vaizdas |
| Klaida skaičiavimuose | Vidutinė | Reputacija, pretenzijos | Testų rinkinys, sutikrinimas su oficialiomis skaičiuoklėmis, aiškus atsakomybės ribojimas |
| Taisyklės keičiasi kasmet | Didelė | Priežiūros darbas kiekvieną sausį | Versijuojamas taisyklių modulis; tai kartu ir barjeras konkurentams |

Atviri klausimai:

- [ ] Patikrinti ir užregistruoti atsidek.lt bei kiekatideti.lt domenus (domreg.lt)
- [ ] Stripe ar Paddle (PVM tvarkymas parduodant ES fiziniams asmenims)
- [ ] Ar teisinis atsakomybės ribojimas pakankamas, ar reikia teisininko peržiūros
- [ ] Ar verta konsultuotis su buhalteriu dėl skaičiavimo variklio patikrinimo
