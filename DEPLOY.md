# Diegimas į Hetzner

Architektūra: vienas VPS, `docker compose` paleidžia `app` (Spring Boot) ir `caddy` (HTTPS su automatiniais Let's Encrypt sertifikatais).

## 1. Serveris

1. Hetzner Cloud → naujas projektas → serveris: **CX22** (arba mažesnis), vieta **Falkenstein / Nuremberg / Helsinki** (ES), Ubuntu 24.04, pridėk savo SSH raktą.
2. Cloud Firewall: leisti tik TCP **22**, **80**, **443** ir UDP **443**. SSH geriau riboti savo IP.
3. Pasirinktinai įjunk Hetzner Backups (+20 % kainos).

## 2. DNS (domreg.lt arba tavo registratoriaus panelėje)

| Vardas | Tipas | Reikšmė |
| --- | --- | --- |
| `atsidek.eu` | A (ir AAAA) | serverio IP |
| `www.atsidek.eu` | A (ir AAAA) | serverio IP |

DNS valdomas Cloudflare. Abu įrašus nustatyk **DNS only** (pilkas debesėlis), kad Caddy pats gautų sertifikatus. Papildomi domenai (`atsidek.lt`, `kiekatideti.lt`) kol kas neužregistruoti; juos užregistravus, reikia pridėti jų DNS ir juos įrašyti į `Caddyfile` kaip nukreipimą į `atsidek.eu`.

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
printf 'CONTROLLER_NAME="Andrius Riauba, IV pažyma Nr. 1440039"\nCONTACT_EMAIL="info@atsidek.eu"\n' > .env
docker compose --profile caddy up -d --build
docker compose logs -f
```

Atidaryk https://atsidek.eu ir patikrink, kad skaičiuoklė skaičiuoja, o https://www.atsidek.eu nukreipia į pagrindinį domeną.

## 5. Atnaujinimas

```bash
cd atsidek && git pull && docker compose --profile caddy up -d --build
```

## Alternatyva: namų serveris (Proxmox) su Cloudflare Tunnel

Viešo IP ir atvirų prievadų nereikia, sertifikatus tvarko Cloudflare.

1. Proxmox: sukurk Debian/Ubuntu LXC (arba VM), 1 CPU, 512 MB–1 GB RAM, 4 GB disko. LXC reikia įjungti *Nesting* (Options → Features), kad veiktų Docker.
2. Įdiek Docker kaip 3 skyriuje, `git clone`, sukurk `.env` (CONTROLLER_NAME, CONTACT_EMAIL).
3. Cloudflare → Zero Trust → Networks → Tunnels → Create tunnel (Cloudflared) → nukopijuok **token**. Public hostname: `atsidek.eu` → HTTP → `app:8080`; antras: `www.atsidek.eu` → tas pats. (DNS įrašus sukuria pats Cloudflare; senus A įrašus tam hostname pašalink.)
4. Į `.env` pridėk `TUNNEL_TOKEN=...` ir paleisk:

```bash
docker compose --profile tunnel up -d --build
```

Caddy šiame variante nenaudojamas. `X-Forwarded-*` antraštes perduoda Cloudflare.

## Dar neaptarta

- Privatumo politikos tekstą peržiūrėti su teisininku; analitika (Plausible), Uptime Kuma, SEO straipsniai, `sitemap.xml`/`robots.txt`.
- El. pašto registracijos forma pašalinta iki mokamos versijos; ji yra git istorijoje (commit 7aac68a), jei reikės grąžinti.
