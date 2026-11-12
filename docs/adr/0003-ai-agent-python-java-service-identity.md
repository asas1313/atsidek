# ADR-0003: How the Python AI agent asserts "this chat is on behalf of logged-in user X" to Java

Status: **Accepted (owner, 2026-11-12).** Recorded here before any B1 code is written. Nothing
in this ADR is implemented yet — this status change only records the decision.

## Context

- `/api/**` (including the already-shipped `POST /api/v1/savings/plan`) is protected by
  `eu.atsidek.auth.ApiKeyFilter`: one shared static secret (`ATSIDEK_API_KEY` /
  `atsidek.api.key-hashes`) for the whole Python `assistant` service, not per end user.
- `SavingsPlanService.plan(request, currentUserId)` resolves `currentUserId` via
  `eu.atsidek.auth.CurrentAppUser#optionalId()`, which reads Spring Security's
  `SecurityContextHolder` — i.e. it only works when the *browser's own* Google-OAuth session
  cookie is present on the request. A server-to-server call from the Python container (which
  has no Spring session) always resolves to anonymous, even for a logged-in user — this is
  exactly the gap A0 decision #6 deferred to B1 ("Python→Java vidinis autentifikavimas... B1").
- Today's `/asistentas` flow is same-origin (`Caddyfile`: `atsidek.eu/asistentas` →
  `assistant:8000`, everything else → `app:8080`), so the browser's Java session cookie
  *is* sent to the Python container today, but Python never reads or validates it — it has no
  way to.
- `docs/specs/monetizacija/PLAN.md` §A.2 (an **unexecuted, un-approved draft**, not part of
  this spec) independently designs the same problem: Java issues a short-lived (15 min),
  signed-but-unencrypted JWT (`sub`, `plan`, `exp`) as an `HttpOnly`/`Secure`/`SameSite=Lax`
  cookie on the apex domain at Google login; Python verifies the signature locally with a
  shared secret (`ATSIDEK_JWT_SECRET`), no network round trip. That plan explicitly flags this
  as an assumption to re-validate before implementation, and it is written for a different
  primary purpose (per-request usage/cost limiting via `POST /api/v1/usage/check`), not for the
  savings-plan tool specifically.
- B1's own text only says "vartotojo id antraštėje, serviso token'as" (user id in a header,
  a service token) — a lighter sketch than the JWT-cookie design, and not obviously the same
  mechanism.

## Problem

Decide, once, how a request that originates in the Python `assistant` process (not the
browser) tells Java's `/api/v1/savings/plan` (and `/api/v1/compare`, for B1) which real,
already Google-authenticated `app_user` it is acting for — in a way that:

1. Cannot be spoofed by an end user who only controls their own browser/chat text (the whole
   point of tier-gating premium data is that the *user* cannot grant themselves premium).
2. Does not require a human to also hold the Python service's static `ATSIDEK_API_KEY` to be
   able to impersonate *any* user (today's single shared secret, if reused naively as the only
   trust anchor for "and also act as user X", would have exactly this weakness).
3. Does not add a second, parallel identity system that the later `monetizacija` work would
   then have to reconcile or throw away.

## Options

**A. Adopt the monetizacija JWT-cookie design now.** Java mints a short-lived signed JWT at
login, set as a same-apex-domain cookie; Python verifies locally with a shared secret and
extracts `sub`. Pros: already designed, reusable later for usage/cost limiting too, no
Java↔Python network hop per message. Cons: pulls forward infrastructure (JWT issuing/rotation,
a second shared secret `ATSIDEK_JWT_SECRET`) from a plan the owner has not yet approved or
scoped for execution; couples B1 to monetizacija's design before that plan itself is decided.

**B. Flip the direction: Java stays the front door.** The chat/tool-calling HTTP endpoint the
browser calls is served by Java (which already has the session), and Java calls into Python
(not the reverse) with the user id/tier it has already resolved, over the existing
`ATSIDEK_API_KEY`-style server-to-server trust (Java is the asserting party, not Python).
Pros: no new per-user credential at all; reuses exactly the trust direction the API-key filter
already has (API key = "this caller is a trusted internal service", which is true of Java
calling Python just as much as Python calling Java today); keeps "numbers never travel through
the LLM, identity never travels through the LLM" as one consistent rule. Cons: reverses which
service fronts the browser for AI features (`Caddyfile`/`docker-compose.yml` routing changes),
which is itself a user-facing architecture decision, not just an auth detail.

**C. Narrow, B-dalis-only header + per-call service token.** Python, having somehow already
established the user's identity for the *current* chat message (see Open Questions — this is
not yet established anywhere for Python today), sends `X-Atsidek-User-Id` plus a *second*,
separate shared secret (distinct from `ATSIDEK_API_KEY`) scoped only to "may assert a user id".
Pros: matches B1's literal wording, smallest change. Cons: a second static shared secret that,
if it ever leaks (e.g. logged, committed, container compromise), lets the holder impersonate
any user to every tier-gated endpoint, forever, until rotated — weaker than A's short-lived JWT
and weaker than B's "Java never needs to trust an externally-asserted id at all".

## Decision

**Option B (Java fronts the browser), refined with a signed context token.**

- A new AI-chat HTTP endpoint lives in **Java**, behind the existing session/OAuth security, not
  in Python. The existing `/asistentas` endpoint is **not** touched or migrated — it keeps its
  current, unauthenticated-identity behaviour unchanged.
- On each incoming chat request, Java resolves the caller's identity the normal way (Spring
  Security session → `CurrentAppUser`), then mints a **short-lived (~5 min) signed context
  token** carrying `{user_id, tier, exp}` and calls the Python `assistant` service at its
  internal address, passing this token.
- Python **does not verify the token and holds no signing/verification secret.** It is purely a
  relay: whenever a tool handler (B1's tax tool, B2's savings tool) needs to call back into a
  Java tier-gated endpoint, it forwards the *same* token it was given, unmodified, in that call.
- Java verifies the token's signature and expiry on every such tool-callback request. An invalid
  signature or an expired token is rejected (this is the trust boundary — Python asserts nothing
  on its own authority; it only ever carries a credential Java itself issued and will itself
  re-check).
- **Mandatory negative test:** a forged or expired token is rejected by Java's verification step.
  This is not optional regardless of which later phase exercises it.
- This resolves option B's open "how does Python's own tool-callback assert identity to Java"
  gap without introducing a second long-lived static shared secret (option C's weakness) and
  without pulling forward the `monetizacija` JWT-cookie design (option A) before that plan itself
  is approved.

## Consequences

- This is the one way user identity crosses the Python/Java boundary for all of B dalis (B1's
  tax tool and B2's savings tool) — not re-decided per tool.
- The new AI-chat endpoint is **Java-fronted**: Caddy/docker-compose routing for it should be
  planned accordingly from the start (affects B4's response-shaping work, since the outer HTTP
  contract now lives in Java, not in Python's `main.py`). Today's `/asistentas` routing
  (`atsidek.eu/asistentas` → `assistant:8000`) is untouched.
- `docs/specs/monetizacija/PLAN.md` §A.2's JWT-cookie design is **not** adopted now; it remains a
  separate, still-unapproved draft for a different purpose (browser-facing usage/cost limiting).
  This ADR's token is an internal Java→Python→Java relay credential, not a browser cookie, and
  is not expected to be reconciled with that plan — they solve different problems.
- `docs/specs/taupymo-tikslai/PLAN-taupymas-agentas.md`'s B1 bullet ("vartotojo id antraštėje,
  serviso token'as") is superseded by this ADR's more specific mechanism; the plan has been
  updated to reference this ADR instead of restating it.
- B1's file list: Java gains token-issuing code (chat endpoint) and token-verification code (on
  the tool-callback endpoints); Python gains no identity code beyond carrying the token string
  through unchanged.
