# Implementation plan: taupymo tikslai — nemokama / prisijungus atskyrimas (increment 1)

Spec: `docs/specs/taupymo-tikslai/PLAN-atskyrimas.md`
Related, overlapping context (not this spec, read for reuse only):
`docs/specs/monetizacija/PLAN.md`, `docs/specs/saugumas/PLAN.md` (see §10 for how this
increment relates to the latter).
ADRs: `docs/adr/0001-open-google-oauth-login-for-public-users.md`,
`docs/adr/0002-entitlements-data-model-and-beta-flag.md`

**Status: APPROVED, implementation starting at task 1. All 7 original open questions are
answered (§4); the scope was expanded (first real premium feature, hard security
requirements) and then the user approved that revision with five further binding fixes —
goal-replacement confirmation + transactional contribution cleanup (§5, tasks 9/10/11),
CSRF required on `/goals/tikslai/preliminarus` and no silent auto-replace on
`/mano-tikslas` (task 6, task 11), `TIMESTAMPTZ`/`Clock.systemUTC()` everywhere (task 1,
task 2), DB `CHECK` constraints + `/api/v1/compare`-equivalent amount validation (task 1,
task 9), and session resilience when an `app_user` row disappears mid-session (task 15) —
all incorporated below. Two related items the user asked to track in
`docs/specs/saugumas/PLAN.md` instead of here (provider_subject-based owner/admin binding;
data access/export surviving entitlement loss) have been added there, not to this plan.**

## 0. Where the project actually is today (vs. the spec's 7 stages)

Checked against `src/main/java/eu/atsidek/goals/**`, `src/main/resources/db/migration/*`,
`pom.xml`, `SecurityConfig.java`, `LegacyDataAdopter.java`, `README.md`, and the original
(already-shipped) `docs/specs/taupymo-tikslai/PLAN.md` (F0–F6):

| Spec stage (`PLAN-atskyrimas.md`) | State |
|---|---|
| 1. Skaičiavimo variklis (Java) | **Done** — `GoalsEngine`, `GoalInput`/`GoalResult`, `/goals/calculate` + `/api/v1/goals/calculate`, `stat_defaults` table, unit tests. Shipped as F1/F2 of the original spec. |
| 2. Nemokama UI dalis | **Mostly done**; this increment adds the missing "Išsaugok ir sek progresą – prisijunk" CTA (§6, task 12). |
| 3. Minimalus prisijungimas | **This increment.** Confirmed: reuse existing Google OAuth (ADR-0001). |
| 4. Teisių (entitlements) sluoksnis | **This increment.** `entitlement` table + `EntitlementService` + global beta flag. |
| 5. Premium funkcijos | **Partially this increment** — scope expanded by the user to include the *first* premium feature end-to-end: a single saved `SAVINGS_TRACKING` goal with manual progress marking (§1, §5). Multi-goal, scenarios, IV linking, PDF export remain out of scope. |
| 6. AI sluoksnis (Python) | **Unchanged** — free-tier `/api/v1/goals/parse`/`explain` and `GOALS_AI_DAILY_LIMIT` stay exactly as they are; no entitlement-awareness added (nothing in Python needs it yet). |
| 7. Matavimai | **Not started**, out of scope. |

Postgres + Flyway (original spec's F0) is already merged (`V1`–`V4`). This increment's
migrations continue the sequence from `V5` (five new migrations, see §6 task 1).

## 1. Standing constraints (apply to this and every future increment)

Restated here because they bind every task below, not just this document:

- **No investment advice.** Never name products, financial instruments, or specific
  banks, anywhere in UI copy, error messages, or AI prompts touched by this work.
- **Nothing in this plan authorizes a production deploy or a `git push`.** Staging-only,
  and only after the user's explicit go-ahead at the time, per repo workflow norms
  (never push to `main`, never force-push, open a PR).
- **Secrets only via environment variables**, never committed — no new secret is
  introduced by this increment (it reuses `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`).
- **No payment integration in this increment** — `entitlement.source` is `BETA` only;
  `PAYMENT` as a source value is reserved for the separate `monetizacija` initiative.

## 2. Summary

This increment reuses the already-existing, already-tested Google OAuth login, opened to
every verified Google account as a new `ROLE_USER` (distinct from the existing
allow-list-gated `ROLE_ADMIN`, which keeps the bank-import module exactly as restricted
as it is today). It introduces an `app_user` table keyed by an internal UUID (not email),
a generic `entitlement` table with a single Java-side `EntitlementService` check point, a
global runtime-togglable "beta mode" flag, and — per the user's scope expansion — the
*first real premium feature end-to-end*: a logged-in user holding `SAVINGS_TRACKING` can
save exactly one savings goal (built from an existing `GoalsEngine` calculation) and mark
actual monthly contributions against it, with the UI showing planned-vs-actual progress.
`SecurityConfig` is rewritten to a default-deny posture (`anyRequest().hasRole("ADMIN")`
as the fallback, with every public and `ROLE_USER` path listed individually), every
new per-user table cascade-deletes through `app_user`, and every cross-user resource
access returns `404` (never `403`, to avoid confirming existence) — enforced by a
dedicated IDOR test. Multi-goal, scenarios, IV linking, PDF export, Python
entitlement-awareness, and all of `monetizacija`'s payment/usage-limit work remain out of
scope.

## 3. Explicitly out of scope / deferred (and why)

- **Multi-goal, scenarios, IV linking, PDF export** — depend on this foundation; each is
  its own PR once this lands. (`SAVINGS_TRACKING`, singular, is now *in* scope — see §2.)
- **Python AI entitlement-awareness** — no premium AI feature (`AI_PLAN_CHAT`) exists yet
  to gate; added together with it later.
- **Measurement/analytics (stage 7)** — independent of login/entitlements.
- **Everything in `docs/specs/monetizacija/PLAN.md`** (payments, subscriptions, AI usage
  metering) — reuses its drafted `entitlement`/`app_setting` shapes, implements none of
  its payment/webhook/metering logic.
- **Magic link / password auth / Keycloak** — not needed; Google OAuth confirmed (§4.1).
- **Admin UI for the beta flag or for viewing entitlements** — toggled via a documented
  manual SQL statement (§6 task 13); a real admin screen is left to whichever initiative
  needs it first.
- **Privacy-policy (`privatumas.html`) rewrite** — the user will write this themselves in
  a **separate PR**. Per §4.6, it is a **hard blocker for production deploy of this
  feature, but not for staging** — this increment may reach staging before that PR lands,
  but must not reach production until it does. Flagged again in §6 task 13/DEPLOY.md and
  §9.
- **The broader `docs/specs/saugumas/PLAN.md` initiative** (data-map audit, PostgreSQL
  Row-Level Security, column encryption, backup/retention policy, security headers,
  CSP/HSTS, dependency scanning, monitoring/alerting, data *export*, and — importantly —
  migrating the existing `bank_*` tables to multi-tenant `ROLE_USER` access) is its own,
  separate increment and deploy, per that document's own header. This plan implements the
  subset of its P0 principles that the user's new requirements below call for *for the
  new tables this increment adds* (UUID + `ON DELETE CASCADE`, query-level ownership
  filtering, 404-not-403, IDOR test suite, default-deny) — see §10 for the exact
  boundary. It does **not** touch `bank_*` tables, does not do the data-map audit, and
  does not add security headers/RLS/encryption/monitoring.

## 4. Resolved decisions (user confirmed, replacing the previous "open questions")

1. **Login method** — confirmed: reuse existing Google OAuth. `ROLE_USER` granted to
   every account with `email_verified=true`; `ROLE_ADMIN` stays allow-list based
   (unchanged meaning/config keys). `app_user` primary key is an internal UUID, **not**
   email; identity columns are `auth_provider` + `provider_subject` (`UNIQUE` together);
   email is stored but is never a key, and no other table ever references email. This
   also settles the identical question (G.4) in `docs/specs/monetizacija/PLAN.md`. See
   ADR-0001.
2. **Anonymous AI daily limit size** — not changed in this increment; `GOALS_AI_DAILY_LIMIT`
   stays as-is.
3. **Whether `SAVINGS_TRACKING` stays free or becomes paid post-beta** — explicitly
   **deferred**; will be decided later from real beta usage data. This increment's design
   bakes in nothing beyond "while beta mode is on, every logged-in user has it" — see
   ADR-0002's "no pricing policy baked in" note.
4. **Beta flag granularity** — confirmed: single global on/off switch
   (`goals_beta_mode_enabled`), not per-feature.
5. **Beta flag default after deploy** — confirmed: **OFF**.
6. **Privacy policy** — the user writes it in a separate PR; it blocks **production**
   deploy of this feature but **not** staging. Documented explicitly in DEPLOY.md
   (§6 task 13) and ADR-0001.
7. **`/paskyra` self-service account page + deletion** — confirmed: **in scope**, built
   in this increment.

No further open questions block implementation; §9 lists a few small, explicitly
non-blocking residual items.

## 5. Scope expansion: `SAVINGS_TRACKING` end-to-end (new, per user's instruction)

- A logged-in user holding the `SAVINGS_TRACKING` entitlement can save **exactly one**
  savings goal, built from an existing `GoalsEngine` calculation result (same
  `GoalType`/`GoalInput` shape already used by `/goals/calculate`).
- They can mark their actual monthly contribution against it (one row per calendar
  month, upsert-on-repeat).
- The view shows: the goal, planned-vs-actually-accumulated amount, and an on-track
  indicator.
- **New tables** (see §6 task 1 for exact DDL): `savings_goal(id UUID, user_id UUID FK
  → app_user, type, amount, target_date, assumptions JSONB, created_at)`,
  `savings_contribution(id, goal_id UUID FK → savings_goal, month, amount; UNIQUE(goal_id,
  month))`. `savings_goal` additionally has `UNIQUE(user_id)` — the "exactly one goal"
  rule is enforced in the database, not only in application code.
- **"Exactly one" is implemented as upsert, not create+delete**: saving a new goal while
  one already exists overwrites it (same row, same `id`). This avoids needing a separate
  delete-goal endpoint for this increment while still giving every goal a stable,
  addressable `id` in the URL — which is required for the IDOR test below.
- **Replacing an existing goal is destructive to its contributions and requires explicit
  confirmation (blocking fix, user-required).** Because the upsert keeps the same `goal.id`,
  `savings_contribution` rows (keyed by `goal_id`) are *not* removed by `ON DELETE CASCADE`
  when a goal is merely replaced in place — they would otherwise silently survive and mix
  old recorded months with a new goal's plan. So: (1) `SavingsGoalService`'s replace path
  deletes all `savings_contribution` rows for that `goal_id` **in the same transaction** as
  the goal update; (2) the UI never replaces silently — before submitting a replacement, it
  shows a confirmation ("Turi esamą tikslą su N įmokų – pakeitus jos bus ištrintos") and the
  replace only proceeds once the user explicitly confirms (see task 10's `confirm` parameter
  and task 11's "pakeisti / palikti esamą" choice, which is the same confirmation reused for
  the post-login carry-through case). No confirmation is needed when there is no existing
  goal — nothing is destroyed.
- **Entitlement + ownership are checked on every request, independently**:
  `EntitlementService.hasFeature(appUserId, SAVINGS_TRACKING)` must be true, **and** the
  repository query itself is scoped `WHERE user_id = :currentUser AND id = :goalId`
  (never "fetch by id, then compare owner in Java") — same principle
  `docs/specs/saugumas/PLAN.md` states for all future per-user data access. A goal id that
  exists but belongs to someone else is indistinguishable, from the HTTP response, from a
  goal id that doesn't exist at all: both return `404`, never `403`.
- **CTA → login → land back on your own calculation.** The "Išsaugok ir sek progresą –
  prisijunk" CTA on `/taupymo-tikslai` must not lose the visitor's in-progress calculation
  across the Google OAuth round trip. Design (see §6 task 11 and §7 for the full
  reasoning): the browser first `POST`s the current `GoalInput` to a new **public**
  endpoint, `/goals/tikslai/preliminarus`, which stores it in the (already-existing)
  `HttpSession`; the page then navigates (full page load, not `fetch`) to `GET
  /mano-tikslas`, which is `ROLE_USER`-protected — since the visitor isn't authenticated
  yet, Spring Security's existing, already-tested "redirect to Google, then replay the
  originally-requested URL" mechanism (the same one `bankPagesRedirectAnonymousToGoogle`
  already exercises today) lands them back on `GET /mano-tikslas` after login, with the
  session (and therefore the pending input) intact. **No custom
  `AuthenticationSuccessHandler` is needed** — this reuses existing infrastructure.
- Explicitly **out of scope still**: multiple goals, scenarios, IV (tax) linking, PDF
  export.

## 6. Tasks (ordered, one commit-sized unit each)

1. **DB migrations**, continuing from `V4`. **All timestamp columns are `TIMESTAMPTZ`**
   (not `TIMESTAMP`) per the user's explicit correction — this app has/will have users
   outside the server's zone, and `TIMESTAMPTZ` stores an unambiguous instant; `Europe/Vilnius`
   is applied only when *displaying* a value, never when storing or comparing one:
   - `V5__app_user.sql` — `app_user(id UUID PRIMARY KEY, auth_provider VARCHAR(20) NOT NULL, provider_subject VARCHAR(255) NOT NULL, email VARCHAR(255) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE (auth_provider, provider_subject))`. `id` generated in Java (`UUID.randomUUID()`), not a DB default, to avoid a Postgres extension dependency.
   - `V6__app_setting.sql` — `app_setting(setting_key VARCHAR(60) PRIMARY KEY, setting_value VARCHAR(200) NOT NULL)`. No seed row (absence = OFF). No timestamp column.
   - `V7__entitlement.sql` — `entitlement(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE, feature VARCHAR(40) NOT NULL, source VARCHAR(20) NOT NULL, valid_from TIMESTAMPTZ NOT NULL, valid_until TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now())` + index on `(user_id, feature)`.
   - `V8__savings_goal.sql` — `savings_goal(id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE, goal_type VARCHAR(30) NOT NULL, amount NUMERIC(14,2) NOT NULL CHECK (amount > 0), target_date DATE, assumptions JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE (user_id))`.
   - `V9__savings_contribution.sql` — `savings_contribution(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, goal_id UUID NOT NULL REFERENCES savings_goal(id) ON DELETE CASCADE, month DATE NOT NULL CHECK (month = date_trunc('month', month)::date), amount NUMERIC(14,2) NOT NULL CHECK (amount >= 0), UNIQUE (goal_id, month))`. `month` stores the first day of the calendar month it represents (a business date, not an instant) — `DATE`, not `TIMESTAMPTZ`, is correct here; the `CHECK` rejects any non-month-aligned date at the DB layer, not just in application code.
   - **Application-level amount validation mirrors `/api/v1/compare`'s existing, unannotated manual check** (`eu.atsidek.web.TaxApiController.checkAmount`, `MAX_AMOUNT = new BigDecimal("100000000")`): reject `null` (where required), negative, and `> MAX_AMOUNT` values with the same `ResponseStatusException(BAD_REQUEST, ...)` shape already used there — applied to both `savings_goal.amount` and `savings_contribution.amount` in `SavingsGoalService`/`SavingsGoalController` (task 9/10), not left to the DB `CHECK` alone (the DB checks are a backstop, not the primary validation surface — they don't produce a friendly 400 message).
2. **Domain types** — new package `eu.atsidek.entitlement`: `Feature` enum
   (`SAVINGS_TRACKING`, `SAVINGS_MULTI_GOAL`, `SAVINGS_SCENARIOS`, `SAVINGS_IV_LINK`,
   `AI_PLAN_CHAT`, `PDF_EXPORT`), `AppUser` record, `Entitlement` record; an injectable
   `java.time.Clock` bean, default **`Clock.systemUTC()`** (not `systemDefaultZone()` —
   confirmed correction: all storage/comparison stays in UTC, `Europe/Vilnius` is a display
   concern only, applied when rendering a date/time in a template, never when reading or
   writing one), overridden to a fixed/mutable clock in tests.
3. **`AppUserStore`** (`JdbcTemplate`): `findByProviderSubject(provider, subject)`,
   `upsert(id, provider, subject, email)` (idempotent — if the `(provider, subject)` row
   exists, only `email` is updated, `id` is preserved). Test: `AppUserStoreTest`
   (Testcontainers): first-login insert, repeat-login no duplicate/no id change,
   email-change-on-repeat-login updates email only.
4. **Custom `OidcUserService` (`AppOidcUserService`)**, replacing today's inline
   `oidcUserService` bean: on `loadUser()`, after the existing "must be verified" check
   (kept, now applying to *everyone*, not just allow-listed emails), it calls
   `AppUserStore.upsert(...)` **directly inside this method** — this is the explicit
   provisioning point the user required, *not* a success-handler/advice/"first request"
   trick. It returns a small wrapper principal, `AppOidcUser implements OidcUser`
   (delegates everything to the real `OidcUser`, adds `appUserId(): UUID` and overrides
   `getAuthorities()` to return `ROLE_USER` always, plus `ROLE_ADMIN` if the email is in
   the existing allow-list/owner config) — so downstream code reads the resolved UUID
   straight off the principal, with no extra DB round trip per request. Test:
   `AppOidcUserServiceTest` — verified+non-allow-listed → `ROLE_USER` only, row created;
   verified+allow-listed → both roles; unverified → still rejected (unchanged behaviour).
5. **`EntitlementStore` + `EntitlementService`** (`eu.atsidek.entitlement`): single check
   point `hasFeature(UUID appUserId, Feature feature)`, using the injected `Clock` for
   `valid_from`/`valid_until` comparisons; reads the beta flag through a short-TTL
   (~30s) cache (keyed off the same `Clock`, not `System.currentTimeMillis()`/sleep) in
   front of a `JdbcTemplate` read of `app_setting`. Test: `EntitlementServiceTest` — pure
   cases with a fixed `Clock` (`betaOffNoRowsGrantsNothing`, `betaOnGrantsAllMatrixFeatures`,
   `expiredRowNotHonoured`, `notYetValidRowNotHonoured`) and a Testcontainers case that
   advances a mutable test `Clock` (not `Thread.sleep`) past the cache TTL after flipping
   the DB row directly, asserting the new value is picked up.
6. **`SecurityConfig` rewrite — default-deny** (ADR-0001): `anyRequest().hasRole("ADMIN")`
   as the final fallback; every other path listed individually:
   - `permitAll()`: `/`, `/skaiciuokle`, `/calculate`, `/privatumas`, `/style.css`,
     `/htmx.min.js`, `/robots.txt`, `/sitemap.xml`, `/favicon.ico`, `/error`,
     `/taupymo-tikslai`, `/goals/calculate`, `/goals/stats/defaults`,
     `/goals/tikslai/preliminarus` — plus two **documented, narrow, pattern-based
     exceptions** kept as-is: `/api/**` (already gated by `ApiKeyFilter`, which runs
     *before* `AuthorizationFilter`, so `permitAll()` here only means "Spring Security
     itself adds no further role requirement on top of the API key") and
     `/straipsniai/**` (an open-ended set of static public article pages served by one
     controller; no sensitive data).
   - `hasRole("USER")`: `/mano-tikslas`, `/goals/tikslai`, `/goals/tikslai/*`,
     `/goals/tikslai/*/irasai`, `/paskyra`, `/paskyra/istrinti`.
   - `hasRole("ADMIN")`: `/importas`, `/suvestine`, `/taisykles`, `/nustatymai` — listed
     explicitly even though the `anyRequest()` fallback would already cover them, purely
     so the route-table whitelist test (task 7) has something concrete to assert against
     for the bank module too.
   - **No change needed** for `/logout`, `/oauth2/authorization/google`,
     `/login/oauth2/code/google`: verified these are handled by `LogoutFilter` /
     OAuth2 client filters, which run *before* `AuthorizationFilter` in the chain, so they
     are unaffected by the new default-deny fallback (same as today, where they are not
     in the `permitAll()` list either and still work).
   - **CSRF matcher must be fixed in the same change**: `csrf().ignoringRequestMatchers(...)`
     currently lists `/goals/**`, which would (incorrectly) exempt the new *authenticated*
     `/goals/tikslai/**` endpoints from CSRF protection. Replace with the exact public-only
     list: `/calculate`, `/api/**`, `/goals/calculate`. The new `ROLE_USER` endpoints
     (including `/goals/tikslai`, `/paskyra/istrinti`) require a CSRF token like every other
     authenticated state-changing endpoint today (e.g. `/nustatymai/istrinti-viska`).
   - **`/goals/tikslai/preliminarus` is explicitly removed from the CSRF-ignore list too
     (blocking fix, user-required) — it is `permitAll()` for *authorization* (anyone, logged
     in or not, may call it) but it still requires a valid CSRF token**, same as any other
     state-changing `POST`. Spring Security issues a CSRF token to a session regardless of
     authentication state, so an anonymous visitor on `/taupymo-tikslai` already has one
     available; `taupymo-tikslai.html` must render it (a `<meta name="_csrf" ...>` /
     `<meta name="_csrf_header" ...>` pair, the same convention as any other authenticated
     form in this app) and the page's JS must send it as a header on the
     `POST /goals/tikslai/preliminarus` call (see task 11's updated flow). Test: `POST
     /goals/tikslai/preliminarus` without a CSRF token is rejected (`403`), with one
     succeeds.
   - Test: extend `AuthTest` — non-allow-listed verified login succeeds and reaches
     `/mano-tikslas` (not `access_denied`); such a login gets `403` on `/importas` etc.;
     `POST /goals/tikslai` while authenticated but without a CSRF token is rejected.
7. **Whitelist-based route-table security regression test** (new test class, e.g.
   `SecurityRouteWhitelistTest`): enumerates every mapping registered in
   `RequestMappingHandlerMapping` and, for each one, performs the request both
   anonymously and as a plain `ROLE_USER` principal; asserts the outcome is "allowed"
   (not `401`/`403`/a redirect to the Google login entry point) **only** for routes
   present in a hand-written allow-list the test itself defines (kept deliberately
   separate from `SecurityConfig`'s own list, so the two must independently agree — this
   is what catches "someone added a route and forgot to secure it" drift, not just
   "someone typo'd the same list twice"). Path-variable routes (`/goals/tikslai/*`, etc.)
   are exercised with a placeholder segment; the assertion is about the authorization
   outcome, not about getting a `200` (a route that is "allowed" by security but then
   legitimately 404s on a bogus id still counts as "allowed" for this test's purposes).
8. **`LegacyDataAdopter` — investigate, document, test** (code already read: it is a
   `@EventListener` on `AuthenticationSuccessEvent` that calls
   `BankStore.adoptLegacy("local", "google:" + sub)` **only** when the logged-in, verified
   email exactly equals the configured `atsidek.auth.owner-email`. This condition is
   independent of the role-grant logic being introduced in task 6, so opening login to
   the public does not, by itself, change its behaviour — but no automated test currently
   proves that.) Add a regression test (`LegacyDataAdopterTest` or an `AuthTest` case):
   seed `"local"` bank data, log in as a verified-but-non-owner user, assert
   `BankStore.accounts(thatUser'sId)` is empty and no adoption occurred; log in as the
   real owner, assert it *does* adopt, unchanged from today. Document in the PR
   description that full removal of this runtime mechanism (replaced by a one-time
   migration) is intentionally deferred to `docs/specs/saugumas/PLAN.md` P0 ("bank modulio
   perėjimas į daugianaudotojišką"), a separate increment.
9. **`eu.atsidek.goals.tracking`** (new package): `SavingsGoalStore` (`JdbcTemplate`,
   every query scoped by `WHERE user_id = ?` — see §5), `SavingsGoalService`
   (entitlement check via `EntitlementService` + ownership-scoped store calls + progress
   computation reusing `GoalsEngine`'s existing math: expected cumulative balance at the
   elapsed number of months vs. the actual sum of recorded contributions, with a simple
   on-track/behind classification — exact thresholds are an implementation detail, not a
   product decision requiring sign-off, see §9). `SavingsGoalService.save(userId, input,
   confirmed)`: if no goal exists yet for `userId`, inserts unconditionally (`confirmed` is
   irrelevant — nothing is destroyed). If one already exists: when `confirmed` is `false`,
   does **not** touch the database and instead returns a result carrying the existing
   goal's contribution count, for the controller to turn into a confirmation prompt; when
   `confirmed` is `true`, deletes all `savings_contribution` rows for that `goal_id` and
   updates the goal row, **in one transaction** (`@Transactional`) — see §5's blocking fix.
   Amount validation (goal amount and contribution amount alike) mirrors `/api/v1/compare`'s
   existing manual check (reject `null`/negative/`> 100000000`) — see task 1.
10. **`SavingsGoalController`**: `POST /goals/tikslai` (body includes a `confirm`
    boolean/checkbox field, default `false`; create-or-replace the caller's one goal —
    see task 9 for the confirmation semantics: a first-time save always succeeds, a
    replacement without `confirm=true` returns a confirmation-needed view/fragment instead
    of saving, naming how many contribution rows will be deleted), `GET /goals/tikslai/{id}`
    (view + progress), `POST /goals/tikslai/{id}/irasai` (record/upsert a month's
    contribution). Every handler: checks `EntitlementService.hasFeature` first (missing
    entitlement → `403`, since the resource category itself isn't secret, only other
    users' instances are — see §5 for why that's `404`, not this), then resolves the goal
    via an owner-scoped store query (missing-or-not-yours → `404`). Tests:
    `SavingsGoalControllerTest` — first save succeeds unconfirmed; saving again without
    `confirm=true` leaves the existing goal and its contributions untouched and returns the
    confirmation prompt instead of a success response; saving again with `confirm=true`
    replaces the goal **and** deletes its prior `savings_contribution` rows (progress then
    computes from zero — the user-required test for §5's blocking fix); record
    contribution; progress view; entitlement-denied (`403`); and the **explicit IDOR
    test**: two distinct logged-in users (`oidcLogin()` with two different subjects),
    user B requests user A's goal id → `404`.
11. **Session carry-through — never auto-replaces an existing goal (blocking fix,
    user-required)**: `POST /goals/tikslai/preliminarus` (public *authorization*-wise but
    CSRF-protected — see task 6 — validates the body with the same validation
    `GoalsService`/`/goals/calculate` already uses, stores it in `HttpSession`);
    `GET /mano-tikslas` (`ROLE_USER`, Thymeleaf) now branches three ways:
    - No pending input in session → render the existing goal + progress, or an empty state.
    - Pending input, **no** existing goal yet → nothing would be destroyed, so it saves
      immediately (`SavingsGoalService.save(userId, pending, confirmed=true)` — `confirmed`
      is moot here since task 9 always allows a first save), clears the session attribute,
      renders the result.
    - Pending input **and** an existing goal already present → does **not** touch either;
      instead renders a choice: "Pakeisti" / "Palikti esamą". "Pakeisti" posts to the same
      `POST /goals/tikslai` endpoint (task 10) with `confirm=true`, using the already-stored
      session input (no need to resend the data from the client); on success it also clears
      the session attribute. "Palikti esamą" posts to a new, tiny `POST
      /goals/tikslai/laukiantis/atmesti` (`ROLE_USER`, CSRF-protected) that only clears the
      pending-input session attribute and redirects back to `/mano-tikslas`, leaving the
      existing goal exactly as it was.
    - If `SAVINGS_TRACKING` isn't granted at all, the pending input is kept harmlessly in
      the session and the page explains the feature needs beta access (unchanged from the
      previous draft).
    Tests: pending input + no existing goal → auto-saved, session cleared; pending input +
    existing goal → goal **not** changed and the choice is rendered, until the user either
    confirms replacement (goal replaced, old contributions deleted, per task 9/10) or
    explicitly keeps the existing one (session cleared, goal untouched). (A true
    full-browser round trip through Google's real login page cannot be simulated in
    `MockMvc`; see §8 risks for why a manual browser check is still required before merge.)
12. **Nav, `AuthModelAdvice`, and the `/taupymo-tikslai` CTA**: add an `isAdmin` model
    attribute; hide the bank-only nav items for `ROLE_USER`-only sessions; add a "Mano
    tikslas" / "Mano paskyra" link when logged in. Add the "Išsaugok ir sek progresą –
    prisijunk" block to `taupymo-tikslai.html` (shown only when `!loggedIn`), wired to the
    two-step JS flow from §5/task 11 (`POST /goals/tikslai/preliminarus`, then navigate to
    `/mano-tikslas`). Update `bank/fragments.html :: nav`, extend `AuthTest`'s nav test,
    extend `GoalsPageControllerTest`.
13. **`AccountController`**: `GET /paskyra` (`ROLE_USER`, shows email + delete button),
    `POST /paskyra/istrinti` (`ROLE_USER` + CSRF, deletes the `app_user` row — relying on
    `ON DELETE CASCADE` for `entitlement`/`savings_goal`/`savings_contribution` cleanup —
    invalidates the session, redirects to `/`). New template `templates/paskyra.html`.
    Test: `AccountControllerTest` — delete removes all four tables' rows for that user
    (verified by direct JDBC query, not just the HTTP response), ends the session,
    anonymous `POST /paskyra/istrinti` is rejected.
14. **`DEPLOY.md` update**:
    - No new secret-bearing env var (reuses `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`).
    - Manual SQL to flip `app_setting.goals_beta_mode_enabled` to `true` in staging
      (default stays OFF after deploy).
    - **Google OAuth consent screen**: keep it in **Testing** mode for staging, with the
      actual test-user Google accounts added explicitly in Google Cloud Console (an
      operational step the user needs to perform — this plan cannot add arbitrary
      people's emails on their behalf); move to **In production** only once the separate
      privacy-policy PR (§4.6) has landed.
    - Redirect URI values for both staging and production, and a recommendation to use a
      **separate OAuth client ID per environment** (do not reuse the same client for
      staging and production).
    - Explicit note: this feature must not reach production until the privacy-policy PR
      lands (§4.6); staging is unaffected by that gate.
15. **Session resilience: `app_user` row deleted mid-session (blocking fix, user-required)**
    — if a user deletes their account in one browser/session (task 13) while still
    authenticated in another, the second session's `SecurityContext` still holds a valid
    `AppOidcUser` principal pointing at a now-nonexistent `app_user.id`; any subsequent
    `ROLE_USER` request must not 500. Add a filter (`MissingAppUserFilter`, registered after
    Spring Security's authentication filters, before the authorization decision reaches a
    controller) that, for an authenticated `AppOidcUser` request, calls
    `AppUserStore.findById(principal.appUserId())`; if absent, it invalidates the `HttpSession`
    and redirects to `/` instead of letting the request reach the controller (which would
    otherwise fail with an unhandled exception trying to load a nonexistent user's data).
    Test: seed a session as an authenticated user, delete that user's `app_user` row directly
    via JDBC (simulating deletion from another session), then hit a `ROLE_USER` endpoint —
    assert a redirect to `/` and an invalidated session, not `500`.
16. **Full regression pass** — complete `mvn test` (Testcontainers Postgres) before
    opening the PR; PR description documents what/why/how-tested, per repo convention,
    and requests review from another agent/subagent. **No push to `main`, no deploy, of
    any kind, without the user's explicit permission at that time** (standing constraint,
    §1).

## 7. Design decisions

- **Identity shape**: `app_user.id` is an internal UUID; `(auth_provider,
  provider_subject)` is the unique external identity, email is data, not a key. Chosen
  specifically so a second identity provider (e.g. a future magic-link option) can be
  added later as a second `(auth_provider, provider_subject)` pair without an ID-type
  migration and without the entitlement/savings schema caring at all — they only ever see
  the UUID.
- **Provisioning lives inside `AppOidcUserService.loadUser()`**, not a success handler,
  advice, or "first request after login" trick, per explicit requirement — this also lets
  the resolved UUID be attached to the returned principal (`AppOidcUser`), avoiding a
  repeated DB lookup on every subsequent request in the session.
- **`CurrentUser` (bank module, `"google:" + sub` string convention) is left completely
  untouched** — the bank module stays on its existing identity convention and
  `ROLE_ADMIN`-only access; the new `AppOidcUser.appUserId()` (UUID) is a parallel,
  unrelated identifier for the new tables. No migration of `bank_*` tables' `user_id`
  column is in scope here (that belongs to `docs/specs/saugumas/PLAN.md`'s own bank
  multi-tenancy item).
- **Default-deny `SecurityConfig`** (`anyRequest().hasRole("ADMIN")`): a newly added route
  that nobody remembers to classify is now admin-only-by-default (fail closed) rather
  than silently public or silently broken for everyone. The two kept wildcard exceptions
  (`/api/**`, `/straipsniai/**`) are each independently justified (separate auth
  mechanism; open-ended but non-sensitive content) and called out explicitly rather than
  left implicit.
- **Ownership checks happen in the SQL `WHERE` clause, not as a Java-side comparison
  after fetching by id alone** — same principle stated in `docs/specs/saugumas/PLAN.md`'s
  P0 section; makes it structurally impossible to "forget" the ownership filter on one
  code path while remembering it on another.
- **Cross-user access returns `404`, never `403`.** `403` would confirm the resource
  exists but isn't yours; `404` reveals nothing. A missing `SAVINGS_TRACKING` entitlement,
  by contrast, returns `403` on the *collection* endpoints (`POST /goals/tikslai`),
  because the existence of the feature itself is not a secret — only other people's data
  is.
- **`ON DELETE CASCADE` on every foreign key into `app_user`, directly or transitively,
  is a standing rule, not a one-off for this increment's tables** — written into
  ADR-0002 as binding on all future tables that hold per-user data (including whatever
  `monetizacija` eventually adds), not just `entitlement`/`savings_goal`/
  `savings_contribution`.
- **"Exactly one goal" enforced by `UNIQUE(user_id)` in the database**, with
  create-or-replace (upsert) semantics in the service layer, instead of a separate
  delete-then-create flow — simplest shape that still gives every goal a stable `id` for
  the IDOR test to target.
- **Session-based carry-through of the pre-login calculation, not a URL parameter** — a
  savings-goal amount/target-date is personal financial information that should not end
  up in a URL, browser history, or `Referer` header. Verified that Spring Boot's default
  session-fixation strategy (`changeSessionId`) preserves session *attributes* across the
  ID rotation that happens on successful authentication, so the pending input survives
  login — this was checked, not assumed, because that assumption drives the whole design
  of task 11.
- **All time comparisons go through an injectable `java.time.Clock`**, per explicit
  requirement — `valid_from`/`valid_until` checks and the beta-flag cache TTL. No test in
  this increment uses `Thread.sleep` or waits on the wall clock.
- **No pricing policy is encoded for `SAVINGS_TRACKING`** beyond "beta flag on + logged
  in ⇒ granted" — per §4.3, deliberately not deciding whether it stays free later.
- **Goal replacement always requires explicit confirmation, never happens silently** — per
  the user's blocking correction, because the original upsert-in-place design would have
  silently left old `savings_contribution` rows attached to a reused `goal.id` after a
  replace, invisibly corrupting the new goal's progress calculation. The same confirmation
  mechanism (task 9/10) is reused for both the direct "save a goal" action and the
  post-login "you already have a goal, pending input is waiting" case (task 11) — one
  rule, two entry points, rather than two separate implementations that could drift apart.
- **All stored timestamps are `TIMESTAMPTZ`, compared via `Clock.systemUTC()`** — per the
  user's correction; `Europe/Vilnius` is applied only at display time (Thymeleaf
  formatting), never in storage or comparisons, so the app is correct regardless of server
  timezone configuration or daylight-saving transitions.
- **`/goals/tikslai/preliminarus` requires CSRF even though it's open to anonymous
  visitors** — "no login required" and "no CSRF required" are different axes; conflating
  them was the original draft's mistake (an attacker-controlled page could otherwise POST
  a crafted calculation into a victim's session). Spring issues CSRF tokens independent of
  authentication state, so this costs nothing for genuinely anonymous use.

## 8. Risks

- **Default-deny regression risk**: the single highest-risk change in this increment —
  getting the explicit lists wrong could lock out the owner, break the free calculator,
  or (worse) silently expose something. Mitigated by task 7's independent whitelist test
  and by running the *entire* existing suite, not just new cases, before the PR.
- **CSRF-matcher risk**: easy to forget that splitting `/goals/**` for authorization also
  requires splitting the CSRF-ignore matcher (task 6) — explicitly called out as its own
  sub-step and its own test, because getting it wrong either breaks the free calculator
  (over-strict) or leaves a real CSRF hole on the new authenticated endpoints
  (under-strict).
- **Session-based login carry-through cannot be fully proven by `MockMvc`** — the
  automated test (task 11) exercises the server-side logic but not a real browser's
  round trip through `accounts.google.com`. A manual browser check (same convention the
  original spec required for UI work) is still needed before merging, specifically for
  this flow.
- **Google OAuth consent screen "Testing" mode** limits staging logins to explicitly
  added test-user emails — an operational step outside this plan's control; without it,
  nobody but the owner can actually exercise `ROLE_USER` flows on staging.
- **Flyway numbering collisions** if other in-flight work also claims `V5`+ before this
  merges — same coordination note as the original spec's F0/F2 handling.
- **`LegacyDataAdopter` behaviour is correct today but un-enforced by any test** until
  task 8 ships; until then, a future refactor could silently change its condition without
  anything failing.
- **GDPR surface keeps growing** — today's cascade covers four tables; once
  `monetizacija`'s `payment`/`subscription` tables exist, the same cascade rule (§7) must
  be applied to them too — not a blocker now, flagged for later.
- **Confirmation-flow regression risk**: two separate entry points (direct
  `POST /goals/tikslai`, and the post-login choice in task 11) must both route through the
  same `confirmed` semantics in `SavingsGoalService` — tested at both entry points (task
  10 and task 11), not just one, so a future change to either controller can't silently
  reopen the "silent replace" bug this fix closes.
- **The new `MissingAppUserFilter` (task 15) runs on every `ROLE_USER` request** — a bug
  in it (e.g. an expensive or failing `AppUserStore.findById` call) would degrade every
  authenticated page, not just account-deletion edge cases; kept deliberately simple (a
  single indexed primary-key lookup) for that reason.

## 9. Remaining minor open items (non-blocking — implementation detail, not sign-off needed)

- Exact "on-track" thresholds/wording for the `SAVINGS_TRACKING` progress view (task 9)
  are an engineering judgment call within already-approved scope, not a new open
  question; easy to adjust after the fact if the user wants different wording once they
  see it on staging.
- The Google Cloud Console test-user list for the "Testing" consent screen (task 14) is
  an operational action item for the user, not a design decision — flagged, not
  blocking the code itself.

## 10. Relationship to `docs/specs/saugumas/PLAN.md`

That document describes a broader, separate, later increment (its own header says so
explicitly: "atskiras inkrementas, atskiras deploy"), including a full personal-data
audit, bank-module multi-tenancy migration, PostgreSQL Row-Level Security evaluation,
column encryption, backup/retention policy, security headers, data *export* (not just
deletion), dependency scanning, and monitoring/alerting. This increment implements, for
the *new* tables/endpoints it introduces only, the subset of that document's P0
principles the user's requirements (above) call for right now: UUID identities with
`ON DELETE CASCADE`, query-level ownership scoping, `404`-not-`403` for cross-user access,
an IDOR test suite, and a default-deny `SecurityConfig`. It does not migrate the existing
`bank_*` tables, does not perform the data-map audit, and does not implement RLS,
encryption, headers, or monitoring — those stay with the `saugumas` initiative's own,
separately-confirmed plan.
