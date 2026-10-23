# Diegimas į Hetzner

Architektūra: vienas VPS, `docker compose` paleidžia `app` (Spring Boot) ir `caddy` (HTTPS su automatiniais Let's Encrypt sertifikatais). El. pašto registracijos kaupiamos `./data/signups.csv`.

## 1. Serveris

1. Hetzner Cloud → naujas projektas → serveris: **CX22** (arba mažesnis), vieta **Falkenstein / Nuremberg / Helsinki** (ES), Ubuntu 24.04, pridėk savo SSH raktą.
2. Cloud Firewall: leisti tik TCP **22**, **80**, **443** ir UDP **443**. SSH geriau riboti savo IP.
3. Pasirinktinai įjunk Hetzner Backups (+20 % kainos) – juose bus ir `data/signups.csv`.

## 2. DNS (domreg.lt arba tavo registratoriaus panelėje)

| Vardas | Tipas | Reikšmė |
| --- | --- | --- |
| `atsidek.lt` | A (ir AAAA) | serverio IP |
| `www.atsidek.lt` | A (ir AAAA) | serverio IP |
| `kiekatideti.lt` | A (ir AAAA) | serverio IP |
| `www.kiekatideti.lt` | A (ir AAAA) | serverio IP |

Sertifikatai išduodami tik kai DNS jau rodo į serverį, todėl pirmiausia sutvarkyk DNS ir palauk (dažniausiai kelios minutės).

## 3. Docker serveryje

```bash
ssh root@SERVERIO_IP
curl -fsSL https://get.docker.com | sh
```

Rekomenduojama sukurti atskirą vartotoją, atjungti prisijungimą slaptažodžiu ir įjungti `unattended-upgrades`.

## 4. Programa

Privatumo politikai reikia duomenų valdytojo (vardas, pavardė arba įmonė) ir kontaktinio el. pašto. Jie paimami iš `.env` failo:

```bash
git clone https://github.com/asas1313/atsidek.git
cd atsidek
printf 'CONTROLLER_NAME="Vardas Pavardė, IV pažyma Nr. ..."\nCONTACT_EMAIL="info@atsidek.lt"\n' > .env
docker compose up -d --build
docker compose logs -f
```

Atidaryk https://atsidek.lt ir patikrink, kad skaičiuoklė skaičiuoja, o https://kiekatideti.lt nukreipia į pagrindinį domeną.

## 5. Atnaujinimas

```bash
cd atsidek && git pull && docker compose up -d --build
```

## 6. Registracijų atsarginė kopija ir BDAR

- Failas: `data/signups.csv` (laikas, el. paštas). Kopijuok kasdien, pvz. `cron`: `rsync` arba `scp` į savo kompiuterį.
- Ištrynimas pagal prašymą: ištrink eilutę iš failo.
- Privatumo politika yra puslapyje /privatumas, nuoroda poraštėje ir prie sutikimo žymės.

## Dar neaptarta

- Privatumo politikos tekstą peržiūrėti su teisininku; analitika (Plausible), Uptime Kuma, SEO straipsniai, `sitemap.xml`/`robots.txt`.
- Registracijų forma ribojama atmintyje: 5 bandymai per valandą vienam IP (po paleidimo iš naujo skaitliukas nunulinamas).
