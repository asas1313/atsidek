# Diegimas į Hetzner

Architektūra: vienas VPS, `docker compose` paleidžia `app` (Spring Boot) ir `caddy` (HTTPS su automatiniais Let's Encrypt sertifikatais).

## 1. Serveris

1. Hetzner Cloud → naujas projektas → serveris: **CX22** (arba mažesnis), vieta **Falkenstein / Nuremberg / Helsinki** (ES), Ubuntu 24.04, pridėk savo SSH raktą.
2. Cloud Firewall: leisti tik TCP **22**, **80**, **443** ir UDP **443**. SSH geriau riboti savo IP.
3. Pasirinktinai įjunk Hetzner Backups (+20 % kainos).

## 2. DNS (Cloudflare)

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

## Alternatyva: namų serveris (Proxmox) su Nginx Proxy Manager

NPM jau priima srautą kaip riaubyne.eu, todėl Caddy ir tunelio nereikia.

1. Proxmox: sukurk atskirą Debian/Ubuntu LXC (arba VM), 1 CPU, 1 GB RAM, 4 GB disko; LXC reikia *Nesting* (Options → Features). Duok jam pastovų LAN IP (DHCP rezervacija).
2. Įdiek Docker (3 skyrius), `git clone`, sukurk `.env`:

```bash
printf 'CONTROLLER_NAME="Andrius Riauba, IV pažyma Nr. 1440039"
CONTACT_EMAIL="info@atsidek.eu"
APP_BIND=0.0.0.0
' > .env
docker compose up -d --build
```

Be profilio paleidžiamas tik `app` (8080). Ugniasiene / Proxmox firewall leisk 8080 tik iš NPM LXC (100) IP.
3. NPM → Proxy Hosts → Add: domenai `atsidek.eu` ir `www.atsidek.eu`, scheme `http`, forward host = naujo LXC IP, port `8080`, įjunk *Block Common Exploits*. SSL skirtuke: Request a new certificate (Let's Encrypt), *Force SSL*, *HTTP/2*, *HSTS*.
4. Cloudflare DNS: `atsidek.eu` ir `www` nukreipk lygiai taip, kaip nukreiptas riaubyne.eu (tas pats viešas IP arba CNAME į jo hostname, ir tas pats Proxied / DNS only nustatymas).
5. `www` nukreipimą į pagrindinį domeną gali padaryti NPM (Redirection Host) arba palikti abu veikiančius.

## Alternatyva: namų serveris su Cloudflare Tunnel (be NPM)

Viešo IP ir atvirų prievadų nereikia, sertifikatus tvarko Cloudflare.

1. Proxmox: sukurk Debian/Ubuntu LXC (arba VM), 1 CPU, 512 MB–1 GB RAM, 4 GB disko. LXC reikia įjungti *Nesting* (Options → Features), kad veiktų Docker.
2. Įdiek Docker kaip 3 skyriuje, `git clone`, sukurk `.env` (CONTROLLER_NAME, CONTACT_EMAIL).
3. Cloudflare → Zero Trust → Networks → Tunnels → Create tunnel (Cloudflared) → nukopijuok **token**. Public hostname: `atsidek.eu` → HTTP → `app:8080`; antras: `www.atsidek.eu` → tas pats. (DNS įrašus sukuria pats Cloudflare; senus A įrašus tam hostname pašalink.)
4. Į `.env` pridėk `TUNNEL_TOKEN=...` ir paleisk:

```bash
docker compose --profile tunnel up -d --build
```

Caddy šiame variante nenaudojamas. `X-Forwarded-*` antraštes perduoda Cloudflare.

## Automatinis diegimas iš GitHub (staging + production)

Namų serveris (Proxmox LXC su Docker ir Cloudflare Tunnel). Workflow: `.github/workflows/ci.yml`.

Eiga: PR → `mvn test`. Merge į `main` → testai → **staging** (`staging.atsidek.eu`) automatiškai ir sustoja. **Production** (`atsidek.eu`) diegiamas tik rankiniu mygtuku: GitHub → Actions → „CI ir diegimas“ → **Run workflow** (šaka `main`). Jei to commit'o image jau sukurtas staging'e (`atsidek:<sha>`), production naudoja būtent jį. Diegia `deploy/deploy.sh`, kuris paleidžia `docker compose` ir tikrina, ar programa atsako.

### Vienkartinis paruošimas

1. **Runner**: tame pačiame LXC (kur Docker) įdiek GitHub self-hosted runner (repo → Settings → Actions → Runners → New self-hosted runner, Linux x64). Konfigūruodamas pridėk etiketę `atsidek`, įdiek kaip servisą (`sudo ./svc.sh install && sudo ./svc.sh start`), pridėk jo vartotoją į `docker` grupę. Runner jungiasi į GitHub pats, prievadų atidaryti nereikia.
2. **Aplinkų failai serveryje** (slaptų reikšmių GitHub'e laikyti nereikia):

```bash
sudo mkdir -p /opt/atsidek/production-data /opt/atsidek/staging-data
sudo tee /opt/atsidek/production.env >/dev/null <<'EOF'
CONTROLLER_NAME="Andrius Riauba, IV pažyma Nr. 1440039"
CONTACT_EMAIL="info@atsidek.eu"
TUNNEL_TOKEN=<esamo tunelio tokenas>
APP_PORT=8080
CANONICAL_HOST=atsidek.eu
NOINDEX=false
DATA_DIR=/opt/atsidek/production-data
EOF
sudo tee /opt/atsidek/staging.env >/dev/null <<'EOF'
CONTROLLER_NAME="Andrius Riauba, IV pažyma Nr. 1440039"
CONTACT_EMAIL="info@atsidek.eu"
TUNNEL_TOKEN=<naujo staging tunelio tokenas>
APP_PORT=8081
CANONICAL_HOST=staging.atsidek.eu
NOINDEX=true
DATA_DIR=/opt/atsidek/staging-data
EOF
sudo chown -R <runner-vartotojas> /opt/atsidek && sudo chmod 600 /opt/atsidek/*.env
```

3. **Staging tunelis**: Cloudflare Zero Trust → Tunnels → Create tunnel (antras) → Public hostname `staging.atsidek.eu` → HTTP → `app:8080`. Rekomenduojama papildomai uždėti Cloudflare Access (Zero Trust → Access → Applications), kad staging matytum tik tu.
4. **Esamas production**: dabartinį rankiniu būdu paleistą stack'ą sustabdyk (`docker compose --profile tunnel down` senoje direktorijoje), kad nesidubliuotų su `atsidek-production` projektu. Naujas projektas naudoja tą patį tunelio tokeną, todėl DNS keisti nereikia.
5. **GitHub Environments** (repo → Settings → Environments): sukurk `staging` ir `production`. Required reviewers privačiame repo veikia tik mokamuose planuose, todėl patvirtinimo funkciją atlieka rankinis „Run workflow“.

### Pastabos

- Self-hosted runner vykdo kodą tavo serveryje, todėl diegimo darbai paleidžiami tik `push` į `main`, o PR vykdo tik GitHub hostinamus testus. Jei repo viešas, Settings → Actions → General palik „Require approval for outside collaborators“.
- Atšaukimas: „Run workflow“ visada diegia `main` galvą, todėl atšaukimui padaryk `git revert` ir paleisk jį iš naujo.

## Dar neaptarta

- Privatumo politikos tekstą peržiūrėti su teisininku; analitika (Plausible), Uptime Kuma, SEO straipsniai, `sitemap.xml`/`robots.txt`.
- El. pašto registracijos forma pašalinta iki mokamos versijos; ji yra git istorijoje (commit 7aac68a), jei reikės grąžinti.

## Banko išrašų importas (PAPILDYNYS-01)

Ekranai (`/importas`, `/suvestine`, `/taisykles`, `/nustatymai`) pagal nutylėjimą **išjungti** (404). Staging `staging.env` faile pridėk:

```bash
BANK_ENABLED=true
BANK_PASSWORD=<ilgas atsitiktinis slaptažodis>   # HTTP Basic (bet koks vartotojo vardas)
BANK_IBAN_SALT=<atsitiktinė druska, nekeisti>    # mokėtojų IBAN maišoms; pakeitus senos taisyklės nebesutaps
```

Duomenys (H2 failas) gyvena `DATA_DIR` kataloge; `deploy.sh` jį sukuria su teisėmis konteinerio vartotojui. Kol nėra paskyrų, yra tik vienas vartotojas – staging naudok tik su nuasmenintais ar savo duomenimis ir palik Cloudflare Access. Production'e `BANK_ENABLED` nejunk.
