# AI Engineer mokymosi planas — Atsidėk AI

Tikslas: per ~16 savaičių (6–10 val./sav.) išmokti AI inžinerijos su Python,
kuriant realų AI funkcionalumą Atsidėk produktui.

Projekto aplankas: `C:\Projects\kodas\atsidek\ai`

---

## Kuo tai skiriasi nuo dabartinio Atsidėk

Dabar AI (Claude Code) yra **įrankis kodui rašyti**, o pats produktas — deterministinė
skaičiuoklė. Dabar AI taps **produkto dalimi**, veikiančia gamyboje.

| | Dabar (klasikinis backend) | Su AI |
|---|---|---|
| Testavimas | Unit testai, `assert` | Evals — klausimų rinkinys, kokybė procentais |
| Klaidos | Exception arba teisingas rezultatas | Užtikrintai neteisingas atsakymas (haliucinacija) |
| Kaina | Fiksuota (serveris) | Kiekviena užklausa kainuoja tokenais |
| Duomenys | Struktūrizuoti | + nestruktūrizuoti dokumentai (VMI, SODRA) |

**Pagrindinė architektūros taisyklė:** LLM niekada pats neskaičiuoja mokesčių.
Jis supranta klausimą → ištraukia duomenis → iškviečia deterministinę skaičiuoklę
kaip įrankį → paaiškina rezultatą žmogiška kalba.

---

## Etapai

### 1 etapas (1–2 sav.) — LLM pagrindai ir Python
- Tokenai, kontekstinis langas, temperatūra, kodėl modeliai haliucinuoja
- Python tiek, kiek reikia
- **Praktika:** laisvo teksto ištraukimas į struktūrizuotą JSON (`istraukimas.py`)

### 2 etapas (3–5 sav.) — Tool use
- Modelis pats sprendžia, kada ir su kokiais parametrais kviesti skaičiuoklę
- **Rezultatas:** pokalbio asistentas, naudojantis tikrą Atsidėk logiką

### 3 etapas (6–9 sav.) — RAG
- Embeddingai, dokumentų skaidymas (chunking), pgvector PostgreSQL'e, paieškos kokybė
- Atsakymai remiantis VMI/SODRA šaltiniais su citatomis
- Duomenų aktualumas — taisyklės keičiasi kasmet

### 4 etapas (10–12 sav.) — Evals
- 50–100 realių IV klausimų rinkinys su teisingais atsakymais
- Tikslumo matavimas, LLM-as-judge, regresiniai testai keičiant promptą ar modelį

### 5 etapas (13–15 sav.) — Produkcija
- Kainos kontrolė, kešavimas, latency
- Stebėjimas (pvz. Langfuse)
- Apsauga nuo prompt injection, BDAR (finansiniai asmens duomenys)

### 6 etapas (16 sav.) — Portfolio ir interviu
- Straipsnis atsidek.eu apie AI asistento kūrimą
- Kodas GitHub'e, atnaujintas LinkedIn
- Interviu imitacijos, system design klausimai

---

## Progresas

- [x] Python įdiegtas
- [x] VS Code paruoštas
- [ ] Python plėtinys + interpretatorius iš `.venv`
- [ ] `pip install anthropic python-dotenv`
- [ ] API raktas (console.anthropic.com — atskirai nuo Pro plano), išlaidų limitas
- [ ] `.env` ir `.gitignore`
- [ ] Paleistas `istraukimas.py`
- [ ] 5–10 savų pavyzdžių testavimui (pradžia evals rinkiniui)

## Kitas žingsnis

Paleisti `istraukimas.py`, pratestuoti su savais pavyzdžiais ir aptarti rezultatus.
Tada — 2 etapas: prijungti esamą Atsidėk mokesčių skaičiavimo logiką kaip įrankį
(reikės parodyti, kuriame faile ji yra).
