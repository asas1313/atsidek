"""Daily message-count limiter for the (not-yet-wired) chat endpoint - cost protection, per
AC-B5-1. Extends this codebase's existing daily-cap limiter pattern (`limiter.py`'s
`RateLimiter`, `goals_limiter.py`'s `GoalsAiLimiter`), neither of which quite fits here:
`RateLimiter` has a per-IP sliding window this feature doesn't need (that concern - per-IP abuse
protection on the chat endpoint itself - stays `RateLimiter`'s job, applied separately at the HTTP
layer; this class does not duplicate it), and `GoalsAiLimiter`'s single GLOBAL counter is too
coarse once logged-in users with per-tier quotas are in the picture.

Design decisions (this module's own call, since the plan states the requirement but not the exact
bucketing):

1. Anonymous traffic shares ONE global daily counter, same philosophy as `GoalsAiLimiter` - a
   single pool, not per-IP, because this class's job is purely a message-count quota (a cost
   cap), not abuse detection. Per-IP granularity for anonymous users is `RateLimiter`'s separate
   , already-existing job.
2. Each logged-in user gets their OWN per-user daily counter, keyed by whatever stable identifier
   the caller passes as `key` (realistically a user id) - NOT shared globally, so one logged-in
   user exhausting their quota can never affect any other logged-in user. The number of tracked
   keys is bounded the same way `RateLimiter` bounds `MAX_TRACKED_IPS`: once the tracked-key count
   exceeds `MAX_TRACKED_KEYS`, entries from days other than today are pruned, so memory does not
   grow unboundedly across days.
3. `tier` is an OPAQUE STRING the caller supplies ("FREE" / "PREMIUM", or `None`/falsy for
   anonymous) - this module never decodes, verifies, or inspects any token. How a real caller
   determines `tier` reliably is explicitly OUT OF SCOPE for this phase (today, nothing in this
   codebase does this correctly - `main.py`'s `RequestContext.tier` is currently a hardcoded
   placeholder, not derived from the ADR-0003 context token). That is deferred to whichever later
   phase actually wires this limiter into `main.py`, mirroring B2's `resolve_income` raising
   rather than guessing, and B4's deliberate non-wiring into `main.py`.
4. An unrecognized tier string defensively gets the STRICTEST configured cap, never unlimited
   access - a typo or a future tier this module doesn't know about must never silently bypass the
   quota.
5. `try_acquire` is meant to be called EXACTLY ONCE per incoming chat message, by a future caller,
   BEFORE the agent loop runs - never once per tool call. AC-B5-1's "N tool calls within one
   handled message consume exactly 1 unit" is satisfied structurally by WHERE this is called, not
   by anything this class tracks about tool-call counts - this class has no notion of tool calls
   at all, and `try_acquire`'s signature deliberately does not accept one.
"""
import threading
from datetime import date
from enum import Enum
from typing import Callable, Optional

MAX_TRACKED_KEYS = 10_000


class Decision(Enum):
    ALLOWED = "allowed"
    DAILY_CAP = "daily_cap"


class ChatLimiter:
    """`anonymous_daily_cap`: size of the single shared anonymous pool. `tier_daily_caps`: map of
    tier string -> that tier's own daily cap (e.g. `{"FREE": 20, "PREMIUM": 200}`). `clock`: for
    tests, so no `date.today()` call is baked into the logic (same pattern as `GoalsAiLimiter`).
    """

    def __init__(
        self,
        anonymous_daily_cap: int,
        tier_daily_caps: dict[str, int],
        clock: Callable[[], date] = date.today,
    ):
        self.anonymous_daily_cap = anonymous_daily_cap
        self.tier_daily_caps = tier_daily_caps
        self._clock = clock
        self._lock = threading.Lock()
        self._anon_day: date | None = None
        self._anon_count = 0
        # key -> (day last used, count consumed that day)
        self._user_counts: dict[str, tuple[date, int]] = {}

    def _tier_cap(self, tier: str) -> int:
        cap = self.tier_daily_caps.get(tier)
        if cap is not None:
            return cap
        # Unrecognized tier: fall back to the strictest configured cap, never unlimited.
        if self.tier_daily_caps:
            return min(self.tier_daily_caps.values())
        return 0

    def try_acquire(self, key: Optional[str], tier: Optional[str]) -> Decision:
        """`tier` falsy -> anonymous: consumes from the single global daily counter, `key`
        ignored. `tier` truthy -> logged-in: consumes from a per-`key` daily counter capped at
        `tier_daily_caps.get(tier, <strictest configured cap>)`. Never raises; cap exceeded
        returns `Decision.DAILY_CAP`.
        """
        with self._lock:
            today = self._clock()

            if not tier:
                if today != self._anon_day:
                    self._anon_day, self._anon_count = today, 0
                if self._anon_count >= self.anonymous_daily_cap:
                    return Decision.DAILY_CAP
                self._anon_count += 1
                return Decision.ALLOWED

            cap = self._tier_cap(tier)
            if len(self._user_counts) > MAX_TRACKED_KEYS:
                self._user_counts = {
                    k: v for k, v in self._user_counts.items() if v[0] == today
                }
            day, count = self._user_counts.get(key, (today, 0))
            if day != today:
                day, count = today, 0
            if count >= cap:
                self._user_counts[key] = (day, count)
                return Decision.DAILY_CAP
            self._user_counts[key] = (day, count + 1)
            return Decision.ALLOWED


__all__ = ["ChatLimiter", "Decision"]
