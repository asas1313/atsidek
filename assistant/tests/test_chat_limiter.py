"""Tests for `app.chat_limiter.ChatLimiter` (AC-B5-1)."""
from datetime import date

from app.chat_limiter import ChatLimiter, Decision


def test_anonymous_calls_share_one_global_pool():
    lim = ChatLimiter(anonymous_daily_cap=2, tier_daily_caps={"FREE": 5, "PREMIUM": 10})
    assert lim.try_acquire(None, None) is Decision.ALLOWED
    assert lim.try_acquire(None, None) is Decision.ALLOWED
    assert lim.try_acquire(None, None) is Decision.DAILY_CAP


def test_different_logged_in_users_get_own_quota():
    lim = ChatLimiter(anonymous_daily_cap=100, tier_daily_caps={"FREE": 1, "PREMIUM": 10})
    assert lim.try_acquire("user-a", "FREE") is Decision.ALLOWED
    assert lim.try_acquire("user-a", "FREE") is Decision.DAILY_CAP
    # "user-b" is unaffected by "user-a" exhausting their own quota.
    assert lim.try_acquire("user-b", "FREE") is Decision.ALLOWED


def test_free_and_premium_have_distinct_caps():
    lim = ChatLimiter(anonymous_daily_cap=100, tier_daily_caps={"FREE": 1, "PREMIUM": 3})
    assert lim.try_acquire("free-user", "FREE") is Decision.ALLOWED
    assert lim.try_acquire("free-user", "FREE") is Decision.DAILY_CAP

    assert lim.try_acquire("premium-user", "PREMIUM") is Decision.ALLOWED
    assert lim.try_acquire("premium-user", "PREMIUM") is Decision.ALLOWED
    assert lim.try_acquire("premium-user", "PREMIUM") is Decision.ALLOWED
    assert lim.try_acquire("premium-user", "PREMIUM") is Decision.DAILY_CAP


def test_day_rollover_resets_anonymous_and_per_user_counters():
    day = [date(2026, 11, 10)]
    lim = ChatLimiter(
        anonymous_daily_cap=1,
        tier_daily_caps={"FREE": 1, "PREMIUM": 1},
        clock=lambda: day[0],
    )
    assert lim.try_acquire(None, None) is Decision.ALLOWED
    assert lim.try_acquire(None, None) is Decision.DAILY_CAP
    assert lim.try_acquire("user-a", "FREE") is Decision.ALLOWED
    assert lim.try_acquire("user-a", "FREE") is Decision.DAILY_CAP

    day[0] = date(2026, 11, 11)

    assert lim.try_acquire(None, None) is Decision.ALLOWED
    assert lim.try_acquire("user-a", "FREE") is Decision.ALLOWED


def test_unrecognized_tier_falls_back_to_strictest_cap_not_unlimited():
    lim = ChatLimiter(anonymous_daily_cap=100, tier_daily_caps={"FREE": 1, "PREMIUM": 10})
    assert lim.try_acquire("mystery-user", "GOLD") is Decision.ALLOWED
    # Strictest configured cap is FREE's 1, so the 2nd call must be rejected, not unlimited.
    assert lim.try_acquire("mystery-user", "GOLD") is Decision.DAILY_CAP


def test_one_message_with_many_tool_calls_consumes_exactly_one_unit():
    """AC-B5-1: `try_acquire` has no notion of tool-call count at all - the API doesn't even take
    one. A future caller is responsible for calling this exactly once per incoming chat message,
    regardless of how many tool calls that message's agent loop makes internally. This test makes
    that absence of behavior explicit by calling it once (simulating "one handled message,
    N tool calls happened inside") and checking the counter only moved by 1."""
    lim = ChatLimiter(anonymous_daily_cap=5, tier_daily_caps={"FREE": 5, "PREMIUM": 5})
    assert lim.try_acquire("user-a", "FREE") is Decision.ALLOWED
    # Only 1 unit consumed so far - 4 more calls should still be allowed before the cap of 5.
    for _ in range(4):
        assert lim.try_acquire("user-a", "FREE") is Decision.ALLOWED
    assert lim.try_acquire("user-a", "FREE") is Decision.DAILY_CAP


def test_cap_exceeded_never_raises():
    lim = ChatLimiter(anonymous_daily_cap=0, tier_daily_caps={"FREE": 0, "PREMIUM": 0})
    assert lim.try_acquire(None, None) is Decision.DAILY_CAP
    assert lim.try_acquire("user-a", "FREE") is Decision.DAILY_CAP


def test_anonymous_pool_ignores_key_different_keys_still_share_one_counter():
    """AC-B5-1 design decision 1: for anonymous (falsy `tier`) traffic, `key` must be ignored -
    two distinct non-None `key` values (e.g. two different anonymous session/device ids) must
    still draw from the SAME global counter, not get their own pool. (The existing
    `test_anonymous_calls_share_one_global_pool` only ever passes `key=None`, which can't tell
    a truly-shared pool apart from a per-key pool that happens to always see the same key.)"""
    lim = ChatLimiter(anonymous_daily_cap=2, tier_daily_caps={"FREE": 5})
    assert lim.try_acquire("device-A", None) is Decision.ALLOWED
    assert lim.try_acquire("device-B", None) is Decision.ALLOWED
    # Cap of 2 already consumed by two DIFFERENT keys -> a third, also-different key must still
    # be rejected if the pool is genuinely shared and key is genuinely ignored.
    assert lim.try_acquire("device-C", None) is Decision.DAILY_CAP


def test_key_none_with_truthy_tier_is_bucketed_like_any_other_key_not_raised():
    """Pins down the behaviour for `key=None` + truthy `tier`, flagged by the implementer as an
    untested edge case: it is NOT special-cased and does NOT raise. It is bucketed under the
    literal dict key `None`, exactly like any other key would be - so if a future wiring bug
    upstream ever passed `key=None` for two DIFFERENT logged-in users (same tier), they would
    incorrectly share one counter. That wiring correctness is explicitly out of scope for this
    module (see module docstring, decision 3); this test only pins down today's actual
    behaviour so a future change to it is a deliberate, visible decision rather than a silent
    regression."""
    lim = ChatLimiter(anonymous_daily_cap=100, tier_daily_caps={"FREE": 2})
    assert lim.try_acquire(None, "FREE") is Decision.ALLOWED
    assert lim.try_acquire(None, "FREE") is Decision.ALLOWED
    assert lim.try_acquire(None, "FREE") is Decision.DAILY_CAP
    assert lim._user_counts[None][1] == 2


def test_empty_tier_daily_caps_falls_back_to_zero_cap_without_crashing():
    """If `tier_daily_caps` is configured empty, `min(self.tier_daily_caps.values())` would raise
    `ValueError` on an empty sequence without the implementation's own guard clause. This pins
    down the fail-closed fallback (cap 0, i.e. immediate `DAILY_CAP`) rather than a crash or
    accidental unlimited access."""
    lim = ChatLimiter(anonymous_daily_cap=10, tier_daily_caps={})
    assert lim.try_acquire("user-x", "FREE") is Decision.DAILY_CAP


def test_max_tracked_keys_pruning_removes_only_stale_day_entries():
    """AC-B5-1: once the tracked-key count exceeds `MAX_TRACKED_KEYS`, entries from days other
    than today are pruned - but TODAY's entries, including ones added on the very call that
    crosses the threshold, must survive. Constructs >MAX_TRACKED_KEYS distinct logged-in users on
    day 1, then rolls over to day 2 and confirms the stale day-1 entries are gone (an old user's
    quota resets as if fresh) while same-day growth never silently drops current entries."""
    from app.chat_limiter import MAX_TRACKED_KEYS

    day = [date(2026, 11, 10)]
    lim = ChatLimiter(
        anonymous_daily_cap=100, tier_daily_caps={"FREE": 5}, clock=lambda: day[0]
    )

    for i in range(MAX_TRACKED_KEYS + 1):
        lim.try_acquire(f"user-{i}", "FREE")
    assert len(lim._user_counts) == MAX_TRACKED_KEYS + 1

    # Still day 1: pruning triggers (len > MAX_TRACKED_KEYS) but everything is today's data, so
    # nothing should be dropped - count should simply keep growing by the new entry.
    lim.try_acquire("user-same-day-extra", "FREE")
    assert len(lim._user_counts) == MAX_TRACKED_KEYS + 2
    assert "user-0" in lim._user_counts

    # Roll over to day 2 and add one new user - pruning should now drop ALL stale day-1 entries,
    # leaving only the just-added day-2 entry.
    day[0] = date(2026, 11, 11)
    lim.try_acquire("user-new-day", "FREE")
    assert len(lim._user_counts) == 1
    assert "user-new-day" in lim._user_counts

    # A day-1 user, now pruned, must be treated as fresh (full new quota), not as if still capped
    # from day 1.
    assert lim.try_acquire("user-0", "FREE") is Decision.ALLOWED
