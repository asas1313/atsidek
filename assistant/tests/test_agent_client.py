"""AC-B4-4: prompt caching (`cache_control` on system + the last tool def) and a logged cache-hit
metric per call - against a fake client, no live API needed, same cadence as `test_agent_loop.py`.
"""
import copy
import logging

from app.agent_client import create_message
from app.savings_tool import TOOL_DEF as SAVINGS_TOOL_DEF
from app.tax_tool import TOOL_DEF as TAX_TOOL_DEF


class FakeMessages:
    def __init__(self, response):
        self._response = response
        self.calls = []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        return self._response


class FakeClient:
    def __init__(self, response):
        self.messages = FakeMessages(response)


class FakeResponseNoUsage:
    """Same shape `test_agent_loop.py`'s own `FakeResponse` has today - no `usage` attribute at
    all - must not make `create_message` raise."""

    def __init__(self):
        self.content = []


class FakeUsage:
    def __init__(self, cache_read_input_tokens, cache_creation_input_tokens):
        self.cache_read_input_tokens = cache_read_input_tokens
        self.cache_creation_input_tokens = cache_creation_input_tokens


class FakeResponseWithUsage:
    def __init__(self, usage):
        self.content = []
        self.usage = usage


def test_system_prompt_is_wrapped_with_an_ephemeral_cache_control_block():
    client = FakeClient(FakeResponseNoUsage())

    create_message(
        client, model="m", system="Tu esi asistentas.", tools=[], messages=[{"role": "user", "content": "hi"}],
        max_tokens=100,
    )

    sent = client.messages.calls[0]
    assert sent["system"] == [
        {"type": "text", "text": "Tu esi asistentas.", "cache_control": {"type": "ephemeral"}}
    ]


def test_only_the_last_tool_definition_gets_a_cache_control_breakpoint():
    client = FakeClient(FakeResponseNoUsage())
    tools = [{"name": "a"}, {"name": "b"}, {"name": "c"}]

    create_message(client, model="m", system="s", tools=tools, messages=[], max_tokens=100)

    sent_tools = client.messages.calls[0]["tools"]
    assert "cache_control" not in sent_tools[0]
    assert "cache_control" not in sent_tools[1]
    assert sent_tools[2]["cache_control"] == {"type": "ephemeral"}


def test_original_tools_list_and_dicts_are_not_mutated():
    """`tax_tool.TOOL_DEF` is a shared module-level constant - mutating it would leak
    `cache_control` into every future caller across the whole process."""
    client = FakeClient(FakeResponseNoUsage())
    original_snapshot = copy.deepcopy(TAX_TOOL_DEF)
    tools = [TAX_TOOL_DEF]

    create_message(client, model="m", system="s", tools=tools, messages=[], max_tokens=100)

    assert "cache_control" not in TAX_TOOL_DEF
    assert TAX_TOOL_DEF == original_snapshot
    assert tools == [TAX_TOOL_DEF]


def test_empty_tools_list_is_passed_through_unchanged_no_crash():
    client = FakeClient(FakeResponseNoUsage())

    create_message(client, model="m", system="s", tools=[], messages=[], max_tokens=100)

    assert client.messages.calls[0]["tools"] == []


def test_call_reaches_the_client_with_the_same_model_messages_and_max_tokens_kwargs():
    client = FakeClient(FakeResponseNoUsage())
    messages = [{"role": "user", "content": "hi"}]

    create_message(client, model="claude-haiku-5-5", system="s", tools=[], messages=messages, max_tokens=1024)

    sent = client.messages.calls[0]
    assert sent["model"] == "claude-haiku-5-5"
    assert sent["messages"] == messages
    assert sent["max_tokens"] == 1024
    assert "tool_choice" not in sent


def test_response_is_returned_unchanged():
    response = FakeResponseNoUsage()
    client = FakeClient(response)

    result = create_message(client, model="m", system="s", tools=[], messages=[], max_tokens=100)

    assert result is response


def test_cache_metrics_are_logged_when_usage_is_present(caplog):
    usage = FakeUsage(cache_read_input_tokens=42, cache_creation_input_tokens=7)
    client = FakeClient(FakeResponseWithUsage(usage))

    with caplog.at_level(logging.INFO, logger="app.agent_client"):
        create_message(client, model="m", system="s", tools=[], messages=[], max_tokens=100)

    [record] = [r for r in caplog.records if r.name == "app.agent_client"]
    assert record.cache_read_input_tokens == 42
    assert record.cache_creation_input_tokens == 7


def test_cache_metrics_log_with_none_fields_when_usage_is_absent_does_not_raise(caplog):
    client = FakeClient(FakeResponseNoUsage())

    with caplog.at_level(logging.INFO, logger="app.agent_client"):
        create_message(client, model="m", system="s", tools=[], messages=[], max_tokens=100)

    [record] = [r for r in caplog.records if r.name == "app.agent_client"]
    assert record.cache_read_input_tokens is None
    assert record.cache_creation_input_tokens is None


# --- QA follow-up: real multi-tool module constants, repeated-call safety -----------------------

def test_both_real_tool_defs_together_neither_module_constant_is_mutated_only_last_gets_cache_control():
    """The task's own strongest correctness property, exercised with BOTH real shared
    module-level TOOL_DEF constants together (not just tax_tool's alone) - a mutation bug here
    would corrupt global state shared by every future call in the process, for either tool."""
    client = FakeClient(FakeResponseNoUsage())
    tax_snapshot = copy.deepcopy(TAX_TOOL_DEF)
    savings_snapshot = copy.deepcopy(SAVINGS_TOOL_DEF)
    tools = [TAX_TOOL_DEF, SAVINGS_TOOL_DEF]

    create_message(client, model="m", system="s", tools=tools, messages=[], max_tokens=100)

    assert TAX_TOOL_DEF == tax_snapshot
    assert "cache_control" not in TAX_TOOL_DEF
    assert SAVINGS_TOOL_DEF == savings_snapshot
    assert "cache_control" not in SAVINGS_TOOL_DEF

    sent_tools = client.messages.calls[0]["tools"]
    assert "cache_control" not in sent_tools[0]
    assert sent_tools[1]["cache_control"] == {"type": "ephemeral"}


def test_repeated_calls_with_the_same_original_tools_list_each_produce_exactly_one_cache_control():
    """Guards against a subtler variant of the mutation bug: `run_agent_loop` passes the SAME
    `tools` list object on every loop iteration - if `create_message` ever mutated it in place,
    repeated calls would accumulate stale/duplicate `cache_control` keys instead of each call
    independently producing a clean single breakpoint on the last tool."""
    client = FakeClient(FakeResponseNoUsage())
    tools = [{"name": "a"}, {"name": "b"}]

    create_message(client, model="m", system="s", tools=tools, messages=[], max_tokens=100)
    create_message(client, model="m", system="s", tools=tools, messages=[], max_tokens=100)

    assert tools == [{"name": "a"}, {"name": "b"}]
    for call in client.messages.calls:
        sent_tools = call["tools"]
        assert "cache_control" not in sent_tools[0]
        assert sent_tools[1]["cache_control"] == {"type": "ephemeral"}


def test_messages_list_itself_is_not_mutated_by_create_message():
    """`messages` is caller-owned conversation state (`agent_loop.py` keeps appending to it
    across iterations) - `create_message` must only read it, not add/remove/reorder entries."""
    client = FakeClient(FakeResponseNoUsage())
    messages = [{"role": "user", "content": "hi"}]
    snapshot = copy.deepcopy(messages)

    create_message(client, model="m", system="s", tools=[], messages=messages, max_tokens=100)

    assert messages == snapshot
