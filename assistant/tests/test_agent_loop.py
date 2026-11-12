"""agent_loop.py (B3): the first genuine multi-turn tool-calling loop in this codebase.

Primary coverage uses a FAKE Claude client (scripted sequence of turns) - no live API call in the
default test run, per the established "every PR runs only unit tests against a fake/mocked
model" cadence (owner decision 3). One additional, clearly-separated live-API test exists at the
bottom, skipped automatically when ANTHROPIC_API_KEY is unset - it is NOT required coverage for
any AC-B3 row, the fake-model tests above it are.
"""
import os
import threading
from types import SimpleNamespace

import pytest

from app.agent_loop import MAX_ITERATIONS, TOO_MANY_STEPS_MESSAGE, run_agent_loop
from app.errors import MissingIncomeError
from app.request_context import RequestContext
from app.tools import ToolRegistry


def text_block(text):
    return SimpleNamespace(type="text", text=text)


def tool_use_block(block_id, name, tool_input):
    return SimpleNamespace(type="tool_use", id=block_id, name=name, input=tool_input)


class FakeResponse:
    def __init__(self, content):
        self.content = content


class FakeMessages:
    def __init__(self, responses):
        self._responses = list(responses)
        self.calls = []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        if not self._responses:
            raise AssertionError("FakeClient called more times than scripted - loop is not bounded")
        return self._responses.pop(0)


class FakeClient:
    """Duck-types `anthropic.Anthropic`'s `.messages.create(...)` shape, scripted turn-by-turn."""

    def __init__(self, responses):
        self.messages = FakeMessages(responses)

    @property
    def calls(self):
        return self.messages.calls


# --- AC-B3-1: iteration limit ------------------------------------------------------------------

def test_max_iterations_is_a_defined_constant_example_value():
    assert MAX_ITERATIONS == 5


def test_model_that_always_requests_another_tool_call_hits_the_limit_not_an_infinite_loop():
    registry = ToolRegistry()
    registry.register("noop", lambda tool_input, ctx, token=None: {"ok": True})
    limit = 3
    responses = [FakeResponse([tool_use_block(f"tu{i}", "noop", {})]) for i in range(limit)]
    client = FakeClient(responses)
    ctx = RequestContext()

    result = run_agent_loop(client, registry, [], ctx, None, "labas", max_iterations=limit)

    assert result.status == "max_iterations_exceeded"
    assert result.final_text == TOO_MANY_STEPS_MESSAGE
    # Bounded: the model was asked exactly `limit` times, never a `limit + 1`-th time.
    assert len(client.calls) == limit


# --- AC-B3-2: tool errors become is_error tool_result blocks ----------------------------------

def test_tool_error_becomes_is_error_tool_result_block_and_loop_recovers():
    registry = ToolRegistry()

    def boom(tool_input, ctx, token=None):
        raise MissingIncomeError("Nėra pajamų - paklausk vartotojo.")

    registry.register("boom", boom)
    client = FakeClient([
        FakeResponse([tool_use_block("tu1", "boom", {})]),
        FakeResponse([text_block("Gerai, paklausiu pajamų.")]),
    ])
    ctx = RequestContext()

    result = run_agent_loop(client, registry, [], ctx, None, "kiek sutaupysiu")

    assert result.status == "completed"
    assert result.final_text == "Gerai, paklausiu pajamų."
    # messages: [0] user text, [1] assistant tool_use turn, [2] user tool_result turn, [3] assistant final.
    tool_result_turn = result.messages[2]
    assert tool_result_turn["role"] == "user"
    assert tool_result_turn["content"] == [{
        "type": "tool_result",
        "tool_use_id": "tu1",
        "content": "Nėra pajamų - paklausk vartotojo.",
        "is_error": True,
    }]
    # The loop made a SECOND model call with the error in hand - it did not crash the turn.
    assert len(client.calls) == 2


def test_unknown_tool_name_dispatches_through_the_registry_not_a_direct_call():
    """Proves dispatch goes through `ToolRegistry.dispatch` (never a dynamic/direct function
    call bypassing it, per the plan's own risk note): an unregistered name only ever raises
    `UnknownToolError` via the registry's own dispatch() path."""
    registry = ToolRegistry()  # nothing registered
    client = FakeClient([
        FakeResponse([tool_use_block("tu1", "does_not_exist", {})]),
        FakeResponse([text_block("ok")]),
    ])
    ctx = RequestContext()

    result = run_agent_loop(client, registry, [], ctx, None, "hi")

    [block] = result.messages[2]["content"]
    assert block["is_error"] is True
    assert "does_not_exist" in block["content"]
    assert result.status == "completed"


def test_non_tool_error_exception_propagates_uncaught_not_swallowed():
    """A bug in a handler (anything NOT a ToolError) must crash the turn, not be papered over by
    a blanket `except Exception`."""
    registry = ToolRegistry()

    def crash(tool_input, ctx, token=None):
        raise ValueError("real bug, not a ToolError")

    registry.register("crash", crash)
    client = FakeClient([FakeResponse([tool_use_block("tu1", "crash", {})])])
    ctx = RequestContext()

    with pytest.raises(ValueError):
        run_agent_loop(client, registry, [], ctx, None, "hi")


# --- AC-B3-3: independent calls in parallel, tax_calc_id-dependent calls after -----------------

def test_independent_tool_calls_run_concurrently_and_dependent_call_waits_for_the_tax_future():
    """Two independent fake tools (no `tax_calc_id` in their input) must both be RUNNING at the
    same time: a `threading.Barrier(2)` only releases once BOTH have started, so a buggy
    sequential implementation would deadlock at the barrier and time out loudly (not hang
    forever, not silently pass). A third, `tax_calc_id`-bearing call must only start once the
    independent batch has fully resolved (recorded via a `threading.Event`)."""
    barrier = threading.Barrier(2, timeout=2)
    tax_done = threading.Event()
    dependent_saw_tax_done = []

    def tax_calc(tool_input, ctx, token=None):
        barrier.wait()
        tax_done.set()
        return {"tax_calc_id": "abc123"}

    def other_independent(tool_input, ctx, token=None):
        barrier.wait()
        return {"ok": True}

    def savings_dependent(tool_input, ctx, token=None):
        dependent_saw_tax_done.append(tax_done.is_set())
        return {"plan": "ok"}

    registry = ToolRegistry()
    registry.register("calculate_iv_taxes", tax_calc)
    registry.register("other_independent", other_independent)
    registry.register("calculate_savings_plan", savings_dependent)

    client = FakeClient([
        FakeResponse([
            tool_use_block("tu_tax", "calculate_iv_taxes", {}),
            tool_use_block("tu_other", "other_independent", {}),
            tool_use_block("tu_dep", "calculate_savings_plan", {"tax_calc_id": "abc123"}),
        ]),
        FakeResponse([text_block("done")]),
    ])
    ctx = RequestContext()

    result = run_agent_loop(client, registry, [], ctx, None, "hi")

    assert result.status == "completed"
    assert dependent_saw_tax_done == [True]


def test_mixed_success_and_failure_in_same_batch_both_results_present_and_correctly_correlated():
    """A gap in the implementer's own suite: every existing is_error test uses a batch of exactly
    ONE tool_use block. This proves that when ONE call in a multi-call batch fails with a
    ToolError, the OTHER call's real (non-error) result is still sent back correctly alongside the
    is_error block - one failure must not corrupt or drop the rest of the batch's response, and
    tool_use_id must not be transposed between the two blocks."""
    registry = ToolRegistry()

    def ok_handler(tool_input, ctx, token=None):
        return {"value": 42}

    def boom_handler(tool_input, ctx, token=None):
        raise MissingIncomeError("Nėra pajamų.")

    registry.register("ok_tool", ok_handler)
    registry.register("boom_tool", boom_handler)

    client = FakeClient([
        FakeResponse([
            tool_use_block("tu_ok", "ok_tool", {}),
            tool_use_block("tu_boom", "boom_tool", {}),
        ]),
        FakeResponse([text_block("done")]),
    ])
    ctx = RequestContext()

    result = run_agent_loop(client, registry, [], ctx, None, "hi")

    assert result.status == "completed"
    blocks = {b["tool_use_id"]: b for b in result.messages[2]["content"]}
    assert len(blocks) == 2
    assert "is_error" not in blocks["tu_ok"]
    assert blocks["tu_ok"]["content"] == '{"value": 42}'
    assert blocks["tu_boom"]["is_error"] is True
    assert blocks["tu_boom"]["content"] == "Nėra pajamų."


def test_batch_result_order_follows_original_tool_use_order_not_completion_order():
    """Two independent calls, deliberately resolving OUT of their submission order (the second
    one submitted finishes FIRST) - the tool_result list sent back to the model must still be in
    the same order as the original tool_use blocks, not completion order, since `tool_use_id`
    correlation is what matters but a reviewer should confirm ordering isn't silently scrambled."""
    import time

    def slow(tool_input, ctx, token=None):
        time.sleep(0.05)
        return {"name": "slow"}

    def fast(tool_input, ctx, token=None):
        return {"name": "fast"}

    registry = ToolRegistry()
    registry.register("slow_tool", slow)
    registry.register("fast_tool", fast)

    client = FakeClient([
        FakeResponse([
            tool_use_block("tu_slow", "slow_tool", {}),
            tool_use_block("tu_fast", "fast_tool", {}),
        ]),
        FakeResponse([text_block("done")]),
    ])
    ctx = RequestContext()

    result = run_agent_loop(client, registry, [], ctx, None, "hi")

    ids_in_order = [b["tool_use_id"] for b in result.messages[2]["content"]]
    assert ids_in_order == ["tu_slow", "tu_fast"]


def test_two_independent_tax_calc_id_free_calls_with_no_dependent_call_also_run_concurrently():
    """Same barrier technique, but with ONLY the independent pair (no dependent call in the
    batch at all) - confirms concurrency doesn't depend on a dependent call being present."""
    barrier = threading.Barrier(2, timeout=2)

    def independent_a(tool_input, ctx, token=None):
        barrier.wait()
        return {"a": True}

    def independent_b(tool_input, ctx, token=None):
        barrier.wait()
        return {"b": True}

    registry = ToolRegistry()
    registry.register("a", independent_a)
    registry.register("b", independent_b)

    client = FakeClient([
        FakeResponse([tool_use_block("tu_a", "a", {}), tool_use_block("tu_b", "b", {})]),
        FakeResponse([text_block("done")]),
    ])
    ctx = RequestContext()

    result = run_agent_loop(client, registry, [], ctx, None, "hi")

    assert result.status == "completed"


# --- Identity relay + registry-based dispatch (B1/B2 pattern mirrored for B3) ------------------

def test_context_token_is_relayed_unmodified_to_every_tool_handler_call():
    received = []
    registry = ToolRegistry()

    def capture(tool_input, ctx, token=None):
        received.append(token)
        return {"ok": True}

    registry.register("capture", capture)
    token = "eyJ1c2VySWQiOiJ4In0.deadbeef"
    client = FakeClient([
        FakeResponse([tool_use_block("tu1", "capture", {})]),
        FakeResponse([text_block("done")]),
    ])
    ctx = RequestContext()

    run_agent_loop(client, registry, [], ctx, token, "hi")

    assert received == [token]
    assert received[0] is token


def test_missing_context_token_is_relayed_as_none():
    received = []
    registry = ToolRegistry()
    registry.register("capture", lambda tool_input, ctx, token=None: received.append(token) or {"ok": True})
    client = FakeClient([
        FakeResponse([tool_use_block("tu1", "capture", {})]),
        FakeResponse([text_block("done")]),
    ])
    ctx = RequestContext()

    run_agent_loop(client, registry, [], ctx, None, "hi")

    assert received == [None]


# --- Termination / tool_choice / baseline shape ------------------------------------------------

def test_model_terminates_immediately_with_text_only_no_tool_use_blocks():
    registry = ToolRegistry()
    client = FakeClient([FakeResponse([text_block("Sveika! Kuo galiu padėti?")])])
    ctx = RequestContext()

    result = run_agent_loop(client, registry, [], ctx, None, "labas")

    assert result.status == "completed"
    assert result.final_text == "Sveika! Kuo galiu padėti?"
    assert len(client.calls) == 1


def test_tool_choice_is_not_forced_to_a_single_tool_model_is_free_to_choose():
    """Design decision 2: `tool_choice` must be 'auto' or simply omitted - NOT forced like
    extractor.py's single-tool pattern."""
    registry = ToolRegistry()
    client = FakeClient([FakeResponse([text_block("hi")])])
    ctx = RequestContext()

    run_agent_loop(client, registry, [{"name": "x"}], ctx, None, "hi")

    call_kwargs = client.calls[0]
    assert "tool_choice" not in call_kwargs or call_kwargs["tool_choice"] == {"type": "auto"}


# --- Gaps: `history` param (documented but previously untested) and `max_iterations` boundary ---

def test_history_is_prepended_before_the_new_user_message():
    """`history` is a documented public kwarg (B5/decision 7's integration point) with zero
    existing test coverage - confirm it is genuinely prepended, in order, ahead of the new
    message, and not silently dropped or appended in the wrong place."""
    registry = ToolRegistry()
    client = FakeClient([FakeResponse([text_block("ok")])])
    ctx = RequestContext()
    history = [
        {"role": "user", "content": "ankstesnė žinutė 1"},
        {"role": "assistant", "content": "ankstesnis atsakymas 1"},
    ]

    result = run_agent_loop(client, registry, [], ctx, None, "naujas klausimas", history=history)

    assert result.messages[0] == history[0]
    assert result.messages[1] == history[1]
    assert result.messages[2] == {"role": "user", "content": "naujas klausimas"}
    sent_messages = client.calls[0]["messages"]
    assert sent_messages[0] == history[0]
    assert sent_messages[1] == history[1]
    assert sent_messages[2]["content"] == "naujas klausimas"


def test_max_iterations_boundary_zero_means_zero_model_calls_and_immediate_exceeded_status():
    """Boundary: `max_iterations=0` must not call the model at all (range(0) is empty) and must
    still return the defined 'exceeded' status rather than e.g. crashing or returning
    status='completed' with final_text=None."""
    registry = ToolRegistry()
    client = FakeClient([])
    ctx = RequestContext()

    result = run_agent_loop(client, registry, [], ctx, None, "hi", max_iterations=0)

    assert len(client.calls) == 0
    assert result.status == "max_iterations_exceeded"
    assert result.final_text == TOO_MANY_STEPS_MESSAGE


def test_max_iterations_boundary_one_means_exactly_one_model_call():
    """Off-by-one guard at the other boundary: `max_iterations=1` with a model that always
    requests a tool call must make exactly one model call, not zero and not two."""
    registry = ToolRegistry()
    registry.register("noop", lambda tool_input, ctx, token=None: {"ok": True})
    client = FakeClient([FakeResponse([tool_use_block("tu0", "noop", {})])])
    ctx = RequestContext()

    result = run_agent_loop(client, registry, [], ctx, None, "hi", max_iterations=1)

    assert len(client.calls) == 1
    assert result.status == "max_iterations_exceeded"


# --- Optional live-API round trip (owner-permitted costed exception for B3/B4/B6 only) ---------

LIVE_TOOL_DEF = {
    "name": "echo_number",
    "description": (
        "Grąžina vartotojo žinutėje nurodytą skaičių. Naudok šį įrankį VISADA, kai vartotojas "
        "žinutėje mini bet kokį skaičių - niekada neatsakyk skaičiumi pats, be įrankio."
    ),
    "input_schema": {
        "type": "object",
        "properties": {"value": {"type": "number", "description": "Skaičius iš vartotojo žinutės."}},
        "required": ["value"],
    },
}


@pytest.mark.skipif(
    not os.getenv("ANTHROPIC_API_KEY"),
    reason="live Anthropic API call - needs ANTHROPIC_API_KEY (optional B3 live-eval exception)",
)
def test_live_round_trip_through_the_real_anthropic_sdk_tool_use_and_tool_result_shapes():
    """NOT required coverage for any AC-B3 row (the fake-model tests above are) - proves the
    loop's content-block conversion survives a round trip through the REAL SDK's actual
    tool_use/tool_result shapes, not just our own fakes' approximation of them."""
    import anthropic

    client = anthropic.Anthropic(api_key=os.environ["ANTHROPIC_API_KEY"], timeout=30, max_retries=1)
    registry = ToolRegistry()
    seen_inputs = []

    def echo_number(tool_input, ctx, token=None):
        seen_inputs.append(tool_input)
        return {"value": tool_input.get("value")}

    registry.register("echo_number", echo_number)
    ctx = RequestContext()

    result = run_agent_loop(
        client, registry, [LIVE_TOOL_DEF], ctx, None,
        "Mano skaičius yra 42. Panaudok echo_number įrankį su šiuo skaičiumi ir pasakyk, kas grąžinta.",
        model="claude-haiku-5-5",
    )

    assert result.status == "completed"
    assert seen_inputs, "modelis turėjo iškviesti echo_number įrankį"
