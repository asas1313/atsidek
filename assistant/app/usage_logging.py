"""Per-request structured usage logging (AC-B5-2, owner decision 9: stdout JSON lines, no new log
aggregation/viewer stood up now - B7 derives averages with a script).

"Per-request" here means per INCOMING CHAT MESSAGE, i.e. one call to `log_usage` aggregates the
WHOLE `run_agent_loop` run for that message (summed/final token counts, total tool-call count,
total wall-clock duration) - this is a different granularity from B4's `agent_client.py`, which
logs a cache-hit/miss metric per individual `messages.create` call within one such run. This
module is the roll-up for the entire run, meant to be called once, after the agent loop for a
message finishes.

Cost is computed with `Decimal` arithmetic throughout - NEVER `float` (CLAUDE.md money rule) -
and returned as a whole number of integer cents.
"""
import json
from decimal import ROUND_HALF_UP, Decimal

# claude-haiku-5-5 pricing, USD per MILLION tokens, as looked up 2026-11-12 from Anthropic's
# pricing page (decision 8: the agent model is fixed to claude-haiku-5-5, no escalation, so no
# other model's pricing is needed here). These are not timeless - re-check Anthropic's pricing
# page if/when the model changes or Anthropic revises rates.
INPUT_RATE_PER_MILLION = Decimal("0.10")
OUTPUT_RATE_PER_MILLION = Decimal("0.50")
# 1.25x the base input rate - Anthropic's standard prompt-cache-write multiplier (5-minute TTL,
# the default; this codebase's agent_client.py does not request the 1-hour TTL).
CACHE_WRITE_RATE_PER_MILLION = Decimal("0.125")
# 0.1x the base input rate - Anthropic's standard prompt-cache-read multiplier.
CACHE_READ_RATE_PER_MILLION = Decimal("0.01")

_MILLION = Decimal(1_000_000)
_CENTS_PER_DOLLAR = Decimal(100)


def _cost_cents(
    input_tokens: int, cache_creation_tokens: int, cache_read_tokens: int, output_tokens: int
) -> int:
    for name, value in (
        ("input_tokens", input_tokens),
        ("cache_creation_tokens", cache_creation_tokens),
        ("cache_read_tokens", cache_read_tokens),
        ("output_tokens", output_tokens),
    ):
        if value < 0:
            raise ValueError(f"{name} must not be negative, got {value}")
    dollars = (
        Decimal(input_tokens) / _MILLION * INPUT_RATE_PER_MILLION
        + Decimal(cache_creation_tokens) / _MILLION * CACHE_WRITE_RATE_PER_MILLION
        + Decimal(cache_read_tokens) / _MILLION * CACHE_READ_RATE_PER_MILLION
        + Decimal(output_tokens) / _MILLION * OUTPUT_RATE_PER_MILLION
    )
    cents = (dollars * _CENTS_PER_DOLLAR).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
    return int(cents)


def log_usage(
    *,
    input_tokens: int,
    cache_creation_tokens: int,
    cache_read_tokens: int,
    output_tokens: int,
    tool_call_count: int,
    duration_ms: int | float,
) -> dict:
    """Builds one structured record (tokens, tool-call count, duration, cost in integer cents),
    writes it as a single JSON line to stdout (owner decision 9), and returns the record so
    callers/tests can inspect it without re-parsing stdout.
    """
    record = {
        "input_tokens": input_tokens,
        "cache_creation_tokens": cache_creation_tokens,
        "cache_read_tokens": cache_read_tokens,
        "output_tokens": output_tokens,
        "tool_call_count": tool_call_count,
        "duration_ms": duration_ms,
        "cost_cents": _cost_cents(
            input_tokens, cache_creation_tokens, cache_read_tokens, output_tokens
        ),
    }
    print(json.dumps(record))
    return record


__all__ = ["log_usage"]
