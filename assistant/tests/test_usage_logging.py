"""Tests for `app.usage_logging.log_usage` (AC-B5-2)."""
import json

from app.usage_logging import log_usage


def test_record_has_all_required_fields_and_exact_cost_no_cache():
    # input: 1_000_000 tokens @ $0.10/M = 10c; output: 1_000_000 @ $0.50/M = 50c; no cache.
    record = log_usage(
        input_tokens=1_000_000,
        cache_creation_tokens=0,
        cache_read_tokens=0,
        output_tokens=1_000_000,
        tool_call_count=2,
        duration_ms=123.4,
    )
    assert record["input_tokens"] == 1_000_000
    assert record["cache_creation_tokens"] == 0
    assert record["cache_read_tokens"] == 0
    assert record["output_tokens"] == 1_000_000
    assert record["tool_call_count"] == 2
    assert record["duration_ms"] == 123.4
    assert record["cost_cents"] == 60


def test_record_with_substantial_cache_reads_and_writes():
    # input: 500_000 @ $0.10/M = 5c
    # cache_creation: 2_000_000 @ $0.125/M = 25c
    # cache_read: 10_000_000 @ $0.01/M = 10c
    # output: 100_000 @ $0.50/M = 5c
    # total = 45c
    record = log_usage(
        input_tokens=500_000,
        cache_creation_tokens=2_000_000,
        cache_read_tokens=10_000_000,
        output_tokens=100_000,
        tool_call_count=1,
        duration_ms=500,
    )
    assert record["cost_cents"] == 45


def test_cost_cents_is_a_genuine_int_never_float():
    record = log_usage(
        input_tokens=123,
        cache_creation_tokens=45,
        cache_read_tokens=67,
        output_tokens=89,
        tool_call_count=0,
        duration_ms=1.0,
    )
    assert isinstance(record["cost_cents"], int)
    assert not isinstance(record["cost_cents"], bool)


def test_rounding_at_half_cent_boundary_uses_round_half_up():
    # input tokens chosen so the raw dollar cost lands on exactly half a cent:
    # input: 50_000 tokens @ $0.10/M = $0.005 = 0.5 cents -> rounds UP to 1 cent.
    record = log_usage(
        input_tokens=50_000,
        cache_creation_tokens=0,
        cache_read_tokens=0,
        output_tokens=0,
        tool_call_count=0,
        duration_ms=0,
    )
    assert record["cost_cents"] == 1


def test_zero_tool_calls_and_zero_duration_does_not_crash():
    record = log_usage(
        input_tokens=10,
        cache_creation_tokens=0,
        cache_read_tokens=0,
        output_tokens=5,
        tool_call_count=0,
        duration_ms=0,
    )
    assert record["tool_call_count"] == 0
    assert record["duration_ms"] == 0
    assert record["cost_cents"] >= 0


def test_independent_hand_computed_cost_scenario_mixed_buckets():
    # Independently hand-computed (not reusing the implementer's fixture numbers):
    # input:        737 @ $0.10/M     -> 0.0000737 $
    # cache_create: 1234 @ $0.125/M   -> 0.00015425 $
    # cache_read:  99999 @ $0.01/M    -> 0.00099999 $
    # output:         42 @ $0.50/M    -> 0.000021 $
    # sum = 0.00124894 $ = 0.124894 cents -> rounds DOWN to 0 cents (below the .5 threshold).
    record = log_usage(
        input_tokens=737,
        cache_creation_tokens=1234,
        cache_read_tokens=99999,
        output_tokens=42,
        tool_call_count=5,
        duration_ms=7,
    )
    assert record["cost_cents"] == 0


def test_rounding_at_half_cent_boundary_via_cache_read_bucket():
    # Independently chosen boundary case using the cache-read rate (0.01/MTok), not the
    # input-rate boundary already covered elsewhere: 500_000 tokens @ $0.01/M = $0.005 = 0.5
    # cents -> ROUND_HALF_UP must push this to 1 cent, not down to 0 (which plain
    # `Decimal.quantize` without `rounding=ROUND_HALF_UP` would do via banker's rounding, since 0
    # is the nearest even integer to 0.5).
    record = log_usage(
        input_tokens=0,
        cache_creation_tokens=0,
        cache_read_tokens=500_000,
        output_tokens=0,
        tool_call_count=0,
        duration_ms=0,
    )
    assert record["cost_cents"] == 1


def test_negative_token_counts_raise_instead_of_producing_a_nonsensical_negative_cost():
    """CLAUDE.md's 'fail loudly, validate inputs at boundaries' rule: a negative token count can
    never be legitimate (Java's own tool results never produce one), and B7 derives cost averages
    straight from these stdout log lines, so silently emitting a negative `cost_cents` would
    quietly corrupt that aggregation instead of surfacing the bug at its source."""
    import pytest

    with pytest.raises(ValueError, match="input_tokens"):
        log_usage(
            input_tokens=-1_000_000,
            cache_creation_tokens=0,
            cache_read_tokens=0,
            output_tokens=0,
            tool_call_count=0,
            duration_ms=0,
        )


def test_negative_cache_or_output_token_counts_also_raise():
    import pytest

    with pytest.raises(ValueError, match="cache_creation_tokens"):
        log_usage(
            input_tokens=0,
            cache_creation_tokens=-1,
            cache_read_tokens=0,
            output_tokens=0,
            tool_call_count=0,
            duration_ms=0,
        )
    with pytest.raises(ValueError, match="cache_read_tokens"):
        log_usage(
            input_tokens=0,
            cache_creation_tokens=0,
            cache_read_tokens=-1,
            output_tokens=0,
            tool_call_count=0,
            duration_ms=0,
        )
    with pytest.raises(ValueError, match="output_tokens"):
        log_usage(
            input_tokens=0,
            cache_creation_tokens=0,
            cache_read_tokens=0,
            output_tokens=-1,
            tool_call_count=0,
            duration_ms=0,
        )


def test_logs_exactly_one_json_line_matching_the_returned_record(capsys):
    record = log_usage(
        input_tokens=1000,
        cache_creation_tokens=200,
        cache_read_tokens=300,
        output_tokens=400,
        tool_call_count=3,
        duration_ms=42.0,
    )
    captured = capsys.readouterr()
    lines = [line for line in captured.out.splitlines() if line.strip()]
    assert len(lines) == 1
    parsed = json.loads(lines[0])
    assert parsed == record
