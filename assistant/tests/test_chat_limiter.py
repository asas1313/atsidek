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
