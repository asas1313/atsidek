"""The one call site for `client.messages.create(...)` (B4) - the module `agent_loop.py`'s own
docstring reserved ("that name is reserved for B4's prompt-caching `cache_control` wiring, which
will likely refactor this").

Wraps the (usually static across one multi-turn exchange) system prompt and tool definitions with
Anthropic prompt-caching `cache_control` breakpoints (AC-B4-4), so repeated calls within the same
`run_agent_loop` turn - and across turns, once a real conversation history is wired in by B5/B7 -
can hit the cache instead of re-processing the same system+tools text every time.
"""
import logging
from typing import Any

logger = logging.getLogger(__name__)


def create_message(
    client: Any,
    *,
    model: str,
    system: str,
    tools: list[dict],
    messages: list[dict],
    max_tokens: int,
) -> Any:
    """Wraps `system` as a cached text block and the LAST tool definition with a cache_control
    breakpoint (AC-B4-4) - caching the (usually static across a conversation) system prompt + tool
    list. Logs a cache-hit/miss metric per call (AC-B4-4) - the actual structured-per-request
    logging format is B5's job (usage_logging.py, not yet built); this just makes the raw metric
    observable now, via the standard `logging` module, not silently dropped.
    """
    wrapped_system = [{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}]

    # Never mutate the caller's list or its dicts in place - `tax_tool.TOOL_DEF`/
    # `savings_tool.TOOL_DEF` are shared module-level constants; mutating them would leak
    # `cache_control` into every future caller across the whole process.
    wrapped_tools = [dict(t) for t in tools]
    if wrapped_tools:
        wrapped_tools[-1]["cache_control"] = {"type": "ephemeral"}

    response = client.messages.create(
        model=model,
        max_tokens=max_tokens,
        system=wrapped_system,
        tools=wrapped_tools,
        messages=messages,
        # `tool_choice` deliberately OMITTED, same as agent_loop.py's own call site (B3 Design
        # decision 2 stays unchanged here).
    )

    # Defensive: some SDK versions/fakes (test_agent_loop.py's own `FakeResponse`) have no
    # `usage` attribute at all - must not raise either way.
    usage = getattr(response, "usage", None)
    cache_read_input_tokens = getattr(usage, "cache_read_input_tokens", None)
    cache_creation_input_tokens = getattr(usage, "cache_creation_input_tokens", None)
    logger.info(
        "anthropic messages.create cache usage",
        extra={
            "cache_read_input_tokens": cache_read_input_tokens,
            "cache_creation_input_tokens": cache_creation_input_tokens,
        },
    )

    return response


__all__ = ["create_message"]
