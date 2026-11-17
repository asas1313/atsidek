# Atsidėk — project-specific rules

These apply on top of `~/.claude/CLAUDE.md`'s global workflow rules.

## Draudžiama

- **Niekada nemerginti PR į `main`** (`gh pr merge` ar bet kuris kitas būdas) — net kai CI žalias,
  net kai PR aprašyme parašyta "ready to merge". Merginu TIK savininkas.
- **Niekada nepush'inti tiesiai į `main`.**
- **Niekada nepaleisti production `workflow_dispatch'o`** (`ci.yml`'o `production` job'as).

**Incidentas (2026-11-17):** PR #50 ir #53 buvo sumerginti į `main` BE savininko leidimo per šią
sesiją/agentą. Kadangi `ci.yml`'o `staging` job'as paleidžiamas AUTOMATIŠKAI kiekvieno push'o į
`main` metu, abu šie merge'ai iškart realiai išdiegė kodą į `staging.atsidek.eu` be savininko
sprendimo — patvirtinta CI #121 ir #125 staging job'ų įvykdymu. Tai NE leistinas veiksmas, net jei
anksčiau panašus veiksmas buvo atliktas be pastabų ar atrodė kaip logiška tąsa po PR aprašymo
parašymo. PR kurti/atnaujinti (`gh pr create`/`gh pr edit`) GALIMA ir REIKIA; sulieti (merge) —
NIEKADA, nepriklausomai nuo CI statuso ar užduoties konteksto.

## Deployment / git

- Work stays on a feature branch; open or update a PR with `gh pr create`/`gh pr edit`, make sure
  CI is green on it, then **stop** and let the owner merge it themselves.
- `ci.yml`'s `staging` job triggers automatically on every push to `main` and redeploys
  `staging.atsidek.eu` — a merge is a deploy in this repo, not just a code change.
- Production deploys are `workflow_dispatch`-only (`ci.yml`'s `production` job) — never trigger
  this.
