# Instrukcija: oficialių skaičiuoklių duomenų surinkimas

Perduok šį failą Claude (naršyklės versijai). Andrius (vartotojas) sėdi šalia ir padeda: gali atidaryti puslapius, įvesti duomenis, paspausti mygtukus. Tikslas – surinkti **oficialius** 2026 m. IV mokesčių skaičiavimo rezultatus, kuriais bus tikrinamas „Atsidėk“ skaičiavimo variklis.

## Taisyklės

- Naudok tik oficialius šaltinius: vmi.lt, sodra.lt, e-seimas.lrs.lt. Skaičiuoklėje įvedinėk tik žemiau nurodytus bandomuosius skaičius, jokių asmens duomenų ir jokio prisijungimo.
- Nieko neišsaugok, nesiųsk ir nepateik oficialiose sistemose (deklaracijų, mokėjimų).
- Jei skaičiuoklė reikalauja prisijungti, sustok ir paprašyk Andriaus nuspręsti.
- Nieko neišgalvok. Jei rezultato negali gauti, rašyk „nepavyko“ ir kodėl. Kopijuok skaičius tiksliai, kaip rodo skaičiuoklė (su centais).
- Visada užrašyk, kokios metų (2025 ar 2026) taisyklės taikomos. Jei skaičiuoklė neleidžia pasirinkti 2026 m., tai pažymėk prie rezultatų.

## 1 dalis. Surask skaičiuokles

1. VMI ir Sodros bendra individualios veiklos mokesčių skaičiuoklė (GPM, PSD, VSD). Surask per vmi.lt arba sodra.lt, paieškos frazė „individualios veiklos mokesčių skaičiuoklė“.
2. Užrašyk: nuorodą, kokiems metams skirta, kokius laukus prašo (pajamos, išlaidos, kitos pajamos, II pakopa ir pan.).

## 2 dalis. Bandomieji scenarijai

Visais atvejais: individuali veikla pagal pažymą, 2026 m., ne verslo liudijimas, ne PVM mokėtojas, pirmų metų ir pensininko lengvatų nėra. „Pajamos“ – metinės IV pajamos. „30 %“ – išlaidos 30 % pajamų. „Kitos pajamos“ – darbo užmokestis metams (jei skaičiuoklė prašo, įvesk kaip metinį bruto; užrašyk, kokį lauką naudojai).

| # | IV pajamos € | Išlaidos | Kitos pajamos € | II pakopa |
| --- | --- | --- | --- | --- |
| 1 | 12 000 | 30 % | 0 | ne |
| 2 | 20 000 | 30 % | 0 | ne |
| 3 | 30 000 | 30 % | 0 | ne |
| 4 | 40 000 | 30 % | 0 | ne |
| 5 | 60 000 | 30 % | 0 | ne |
| 6 | 60 000 | faktinės 15 000 | 0 | ne |
| 7 | 100 000 | 30 % | 0 | ne |
| 8 | 30 000 | 30 % | 40 000 | ne |
| 9 | 60 000 | 30 % | 60 000 | ne |
| 10 | 40 000 | 30 % | 0 | taip |

Jei skaičiuoklė negali atlikti kurio nors scenarijaus (pvz., nepriima kitų pajamų), parašyk „nepavyko“ ir kodėl, o likusius atlik.

Kiekvienam scenarijui užrašyk: apmokestinamąsias pajamas, GPM, VSD, PSD, II pakopos įmoką (jei yra), bendrą sumą, ir ar skaičiuoklė rodo tarpinius veiksmus (pvz. Sodros bazę, GPM bazę, taikytą tarifą).

## 3 dalis. Klausimai pagal įstatymus ir Sodros taisykles

Atsakyk trumpai ir pateik nuorodą su straipsnio numeriu arba citata (iki 15 žodžių):

1. Mokesčio kredito / efektyvaus tarifo formulė IV pajamoms intervale 20 000–35 000 € ir 35 000–42 500 € (2026 m.). Kaip tiksliai apskaičiuojamas GPM?
2. Ar 2026 m. sumokėtos arba priskaičiuotos VSD ir PSD įmokos atimamos iš IV apmokestinamųjų pajamų prieš skaičiuojant GPM?
3. Kaip IV pajamos sumuojamos su darbo ir nuomos pajamomis taikant 20/25/32 % tarifus (36 ir 60 VDU ribos)? Ar 5–20 % kredito schema taikoma tik IV pajamoms, ar bendrai sumai?
4. Minimalios VSD ir PSD įmokos: ar, kai pajamos mažos, taikoma minimali mėnesio įmoka ir VSD (100,54 €/mėn. be II pakopos, 135,13 € su ja), ar tik PSD (80,48 €/mėn.)? Nuo kokios bazės skaičiuojama?
5. Pirmųjų veiklos metų įmokų lengvata ir pensininkų išimtys: trumpai, kam ir kiek taikoma.

## 4 dalis. Grąžinimo formatas

Pateik vieną Markdown atsakymą, kurį Andrius nukopijuos atgal į Claude Code sesiją:

```
## Šaltiniai
- skaičiuoklė: <nuoroda>, metai: <2025/2026>, pastabos: <ką prašė įvesti>

## Rezultatai
| # | Apmokestinamosios IV | GPM | VSD | PSD | II pakopa | Iš viso | Pastabos |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | ... |

## Atsakymai
1. ...
2. ...
3. ...
4. ...
5. ...

## Neaiškumai / nepavyko
- ...
```
