# ADR-0001: Reuse existing Google OAuth login for the public "logged-in" tier

Status: **Accepted** — confirmed by the user (see
`docs/specs/taupymo-tikslai/taupymo-tikslai-atskyrimas.plan.md` §4.1). Production
go-live of the resulting feature is still gated on a separate privacy-policy PR (§4.6);
that gate applies to deploying to production, not to accepting this decision or to
staging.

## Context

`docs/specs/taupymo-tikslai/PLAN-atskyrimas.md` (stage 3, "Minimalus prisijungimas") asks
to compare magic link (email), Spring Security username/password, and Keycloak, and pick
the simplest for a solo developer. It did not mention that the repository already has a
working, production-used Google OAuth2 login (`eu.atsidek.auth.SecurityConfig`,
`eu.atsidek.auth.AuthModelAdvice`, tested in `AuthTest.java`), currently restricted by an
email allow-list (`atsidek.auth.allowed-emails` / `owner-email`) and used only to gate the
single-owner bank-import module (`eu.atsidek.bank.*`).

`docs/specs/monetizacija/PLAN.md` (section B.1/B.2, written independently for a different
but overlapping initiative) reached the same conclusion from a different angle. The user
confirmed this approach for both initiatives at once.

## Decision

Reuse the existing Google OAuth2 integration; do not build a new login mechanism.

### Identity model (confirmed)

- `app_user.id` is an **internal UUID**, generated in application code
  (`UUID.randomUUID()`) on first login — **not** email, and not the bank module's
  `"google:" + sub` string.
- `auth_provider` (e.g. `"google"`) + `provider_subject` (the OIDC `sub`) together are
  `UNIQUE` and are the only way the row is looked up by login code.
- `email` is stored (for display/contact) but is **never** a key — no other table may
  reference a user by email. This also leaves room for a second identity provider later
  (e.g. magic link) as a second `(auth_provider, provider_subject)` pair, without any
  ID-type migration or change to the entitlement/savings schema, which only ever consume
  the UUID.
- `eu.atsidek.bank.CurrentUser` and the `bank_*` tables' `"google:" + sub` string
  convention are **left untouched** by this decision — they are a separate, pre-existing
  identity representation for the admin/bank module only. Unifying the two is explicitly
  out of scope here (see `docs/specs/saugumas/PLAN.md`'s own bank multi-tenancy item for
  where that eventually belongs).

### Role model (confirmed)

- A custom `OidcUserService` (`AppOidcUserService`) replaces today's inline bean. Inside
  its `loadUser()` — and only there, never in a success handler, a `@ControllerAdvice`,
  or a "first request after login" trick — it:
  1. Performs the existing "must be `email_verified`" check (kept, now gating *every*
     login, not just allow-listed ones).
  2. Upserts the `app_user` row (provisioning happens here, as explicitly required).
  3. Returns a wrapper principal, `AppOidcUser implements OidcUser`, exposing
     `appUserId(): UUID` and overriding `getAuthorities()` to grant `ROLE_USER` to every
     verified login, plus `ROLE_ADMIN` additionally when the email is in the existing
     allow-list/owner configuration (unchanged config keys, now interpreted as "who may
     use the bank-import module" rather than "who may log in at all").
- Attaching the UUID to the principal avoids a repeated `AppUserStore` lookup on every
  subsequent request in the session.

### Authorization model (confirmed: default-deny)

- `SecurityConfig.authorizeHttpRequests` is rewritten to `anyRequest().hasRole("ADMIN")`
  as the **final fallback** — any route nobody explicitly classifies is admin-only by
  default (fail closed), not silently public or silently broken.
- Every public and every `ROLE_USER` path is listed **individually**. Two narrow,
  explicitly-justified pattern exceptions remain: `/api/**` (already gated by
  `ApiKeyFilter`, which runs before `AuthorizationFilter`) and `/straipsniai/**` (an
  open-ended set of non-sensitive static article pages served by one controller).
- The bank-import paths (`/importas`, `/suvestine`, `/taisykles`, `/nustatymai`) require
  `hasRole("ADMIN")` explicitly (redundant with the fallback, kept for the route-table
  whitelist regression test's benefit — see the plan's §6 task 7).
- A dedicated test enumerates every registered Spring MVC route
  (`RequestMappingHandlerMapping`) and asserts, against an independently hand-written
  allow-list inside the test itself, that anonymous and plain-`ROLE_USER` requests are
  rejected everywhere except that list — catching "added a route, forgot to secure it"
  drift, not just a duplicated typo.
- The CSRF-ignore matcher (previously a blanket `/goals/**`) is split the same way: only
  the genuinely public JSON endpoints (`/calculate`, `/api/**`, `/goals/calculate`) are
  CSRF-exempt. **`/goals/tikslai/preliminarus` is deliberately *not* on this list despite
  being `permitAll()` for authorization** (confirmed correction) — "no login required" and
  "no CSRF required" are different axes, and conflating them here would let an
  attacker-controlled page POST a crafted calculation into a victim's session; Spring
  issues a CSRF token to anonymous sessions too, so this costs genuinely anonymous use
  nothing. Every new `ROLE_USER` endpoint requires a CSRF token like any other
  authenticated state-changing endpoint in this app.

### `LegacyDataAdopter` (investigated, documented, not changed in behaviour)

Read in full: it is an `@EventListener` on `AuthenticationSuccessEvent` that calls
`BankStore.adoptLegacy("local", "google:" + sub)` **only** when the verified login email
exactly equals the configured `atsidek.auth.owner-email`. This condition is independent
of the new role-grant logic, so opening login to the public does not, by construction,
change when it fires. A new regression test (plan §6 task 8) proves a non-owner login
never adopts legacy data and that user never sees it. Fully replacing this runtime
mechanism with a one-time migration (and deleting the listener) is intentionally deferred
to `docs/specs/saugumas/PLAN.md` P0, a separate, later increment.

### Environment separation (new, confirmed)

- Use a **separate Google OAuth client ID per environment** (staging vs. production) —
  do not reuse one client for both.
- Keep the OAuth consent screen in **Testing** mode (explicit test-user emails added in
  Google Cloud Console) for staging; move it to **In production** only once the separate
  privacy-policy PR (§4.6 of the plan) has landed. This is a **production deploy gate**,
  not a staging gate.

## Consequences

- Zero new authentication *mechanism* to secure/test, but the role/authorization model
  around it changes substantially (default-deny), which is the main source of regression
  risk for this increment (see plan §8).
- Users without a Google account still cannot register; unchanged from before, and not
  revisited by this ADR (§4.1 answers this for now).
- The privacy policy (`privatumas.html`) needs updating for the wider audience; the user
  will do this in a separate PR, gating production only (§4.6).

## Alternatives considered

- Magic link (email): no transactional-email infrastructure exists in the project today;
  rejected as disproportionate effort — remains available later as a second
  `(auth_provider, provider_subject)` pair without touching the entitlement model.
- Spring Security username/password: brute-force protection, reset flow, and CLAUDE.md's
  "fail loudly" bar make this the highest-effort option for a solo developer — rejected.
- Keycloak: a whole new service/DB/attack surface for this product's size — rejected per
  CLAUDE.md's self-hosted-infrastructure-minimalism preference.
