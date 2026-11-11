# Ship log: taupymo-tikslai-atskyrimas

Spec: docs/specs/taupymo-tikslai/PLAN-atskyrimas.md
Branch: feat/taupymo-tikslai-atskyrimas (from main @ 5985c7e)

| Phase | Outcome | Iterations | Notes |
|-------|---------|------------|-------|
| Branch | done | - | created from up-to-date main |
| Plan | approved | 2 revisions | increment 1 = entitlements foundation + SAVINGS_TRACKING end-to-end; plan+ADRs at docs/specs/taupymo-tikslai/taupymo-tikslai-atskyrimas.plan.md, docs/adr/0001, docs/adr/0002 |
| Implement | done | - | all 16 tasks shipped, one commit per task (see plan §6) |
| Test | done | - | tester found+closed 1 real bug (goal amount=0 → 500, fixed to 400) plus 3 coverage gaps; full suite green (192 Java + 49 Python) |
| Review | approved | 1 | reviewer APPROVED on first pass, no findings |
| Merge | done | - | PR #28 `feat: open Google login + entitlements + SAVINGS_TRACKING (increment 1)` merged into main 2026-11-11; follow-ups PR #29 (hidden/.small CSS fix) and PR #30 (docs/specs reorg) also merged |

**Increment 1 is fully complete.** Remaining items are non-coding / explicitly deferred, not a next task in this plan (see plan §8–§9, §3):

- Manual browser check of the real Google OAuth round trip (`/taupymo-tikslai` → `/mano-tikslas`) — not exercisable via `MockMvc`.
- Google Cloud Console: add staging test-user emails while the OAuth consent screen is in "Testing" mode.
- Privacy-policy PR (user-authored) — blocks **production** deploy of this feature, not staging.
- Anything in plan §3 (multi-goal, scenarios, IV linking, PDF export, Python AI entitlement-awareness, `monetizacija`, `saugumas`) is explicitly a **separate, future increment**, not a continuation of this plan.
