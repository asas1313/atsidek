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
