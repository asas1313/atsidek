# Ship log: taupymas-agentas (A dalis + B dalis, mokesčiai + taupymas AI agentas)

Spec: docs/specs/taupymo-tikslai/PLAN-taupymas-agentas.md
Branch: feat/taupymas-agentas-a0 (from main @ origin/main)

| Phase | Outcome | Iterations | Notes |
|-------|---------|------------|-------|
| Branch | done | - | created from up-to-date main |
| A0 status check | done | - | compared repo vs. `spec.md` + referenced `docs/PLAN-taupymas.md` (not found in repo — flagged, not substituted); wrote `docs/STATUS-taupymas.md` with the feature → status → code → gap table for A1–A6; checked both A0-required live facts (staging network unreachable from this sandbox; `/api/v1/compare`'s `TaxResult.monthlyReserve` already covers the tax-setaside fact, net-monthly-income does not exist as a field) |
| A0 decisions | done | - | owner answered all 3 open questions 2026-11-11; recorded in `PLAN-taupymas-agentas.md` ("A0 sprendimai") and `STATUS-taupymas.md`; proceeding to A1–A5 |
| A1 | done | 2 | PR #33 (feat/taupymas-agentas-a1 → feat/taupymas-agentas-a0). Default annual return 3%→0% in `GoalsEngine` + `StatDefaultsService`; UI slider/sources fixed to match; test coverage strengthened (WHEN+rm=0, CHILD_AGE, non-tautological assertions, constant cross-check). DB-impact reported, no migration (owner decision pending). Could not run full `make test` (Testcontainers, no Docker in sandbox) — flagged in PR for owner/CI to verify. Review round 1: CHANGES REQUESTED (SHOULD×2, NIT×2); round 2: APPROVED. |
| A2 | done | 1 | branch feat/taupymas-agentas-a2 (stacked on a1). Audited all 16 `stat_defaults` rows: already honest (all `is_illustrative=true`, non-blank sources, no fabricated data) — no seed changes needed. Added `hasTraceableOrigin()` invariant + guard test. Review round 1: APPROVED (2 NIT only). |
| A3 | pending | 0 | branch not yet created |
| A4 | pending | 0 | branch not yet created |
| A5 | pending | 0 | branch not yet created |
| A6 | pending | 0 | staging unreachable from this sandbox — will produce curl checklist instead of live verification |
