"""POST /internal/chat, AGENT_CHAT_ENABLED=true path (B7 wiring) - the real multi-step agent
(B3 loop + B4 response templates/system prompt + B5 chat_limiter/usage_logging) actually wired
together for the first time, behind a feature flag whose rollback is just flipping it back off.

AGENT_CHAT_ENABLED=false's behavior is unchanged B1-era stub code, already covered by
test_internal_chat.py - not duplicated here.
"""
import json
from decimal import Decimal
from types import SimpleNamespace

import httpx
from anthropic import APIConnectionError
from fastapi.testclient import TestClient

from app.chat_limiter import ChatLimiter
from app.extractor import Extraction
from app.main import create_app


def text_block(text):
    return SimpleNamespace(type="text", text=text)


def thinking_block(text="...", signature="sig-1"):
    """Claude Haiku 5.5 has adaptive thinking on by default (see `agent_loop._content_block_to_dict`'s
    own note) - a `signature` is cryptographically bound to the exact conversation prefix that
    existed when the block was generated."""
    return SimpleNamespace(type="thinking", thinking=text, signature=signature)


def tool_use_block(block_id, name, tool_input):
    return SimpleNamespace(type="tool_use", id=block_id, name=name, input=tool_input)


class FakeResponse:
    def __init__(self, content, stop_reason="end_turn"):
        self.content = content
        self.stop_reason = stop_reason
        self.usage = SimpleNamespace(
            input_tokens=10, output_tokens=5, cache_creation_input_tokens=0, cache_read_input_tokens=0,
        )


class FakeMessages:
    def __init__(self, responses=None, error=None):
        self._responses = list(responses or [])
        self._error = error
        self.calls = []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        if self._error is not None:
            raise self._error
        return self._responses.pop(0)


class FakeAgentClient:
    def __init__(self, responses=None, error=None):
        self.messages = FakeMessages(responses, error)


class FakeCalc:
    """Both `compare` (tax tool) and `savings_plan` (savings tool) - the old test_internal_chat.py
    FakeCalc only had the first, since the stub path never calls the second."""

    def __init__(self):
        self.compare_calls = []
        self.savings_calls = []

    def compare(self, revenue, actual_expenses, other_income, pension_accumulation, psd_min_months,
                context_token=None):
        self.compare_calls.append(
            (revenue, actual_expenses, other_income, pension_accumulation, psd_min_months, context_token))
        d = Decimal
        better = {"ruleYear": 2026, "method": "FLAT_30", "revenue": d(0), "expenses": d(0),
                  "taxableIncome": d(0), "gpm": d(0), "extraGpm": d(0), "vsd": d(0), "psd": d(0),
                  "pensionAccumulation": d(0), "total": d("500.50"), "monthlyReserve": d("41.71"),
                  "effectiveRate": d("0.25"), "netMonthlyIncome": d("458.79")}
        return {"flat": better, "actual": better, "better": better}

    def savings_plan(self, goal, net_monthly_income, monthly_tax_setaside, monthly_expenses,
                      household, assumptions, context_token=None):
        self.savings_calls.append((goal, net_monthly_income, monthly_tax_setaside, monthly_expenses,
                                    household, assumptions, context_token))
        # Owner decision, 2026-11-17 (first real staging walkthrough): mirrors real Java
        # (SavingsPlanService#buildCore) - income absent means the contribution is still
        # computed, but the free-cash fit fields come back null, plus the invitation tip.
        if net_monthly_income is None:
            core = {
                "monthly_contribution": Decimal("100.00"),
                "free_cash_monthly": None,
                "share_of_free_cash_pct": None,
                "general_tip": "Jei norite sužinoti, ar tai telpa į biudžetą, nurodykite savo pajamas.",
                "assumptions": [],
                "disclaimer": "Tai tik iliustracija, ne finansinė konsultacija.",
                "reachable": True,
                "reach_date": None,
                "fits": None,
            }
            return {"tier": "FREE", "core": core, "locked_features": []}
        core = {
            "monthly_contribution": Decimal("100.00"),
            "free_cash_monthly": Decimal("500.00"),
            "share_of_free_cash_pct": Decimal("20.0"),
            "general_tip": "Šis tikslas atrodo pasiekiamas.",
            "assumptions": [],
            "disclaimer": "Tai tik iliustracija, ne finansinė konsultacija.",
            "reachable": True,
            "reach_date": None,
            "fits": True,
        }
        return {"tier": "FREE", "core": core, "locked_features": []}


def client(agent_client=None, calc=None, chat_limiter=None):
    calc = calc or FakeCalc()
    app = create_app(calculator=calc, agent_client=agent_client, chat_limiter=chat_limiter,
                      agent_chat_enabled=True)
    return TestClient(app), calc


# --- tax-only --------------------------------------------------------------------------------

def test_tax_only_calls_calculate_iv_taxes_and_returns_a_reply_built_from_its_result():
    fake = FakeAgentClient([
        FakeResponse([tool_use_block("tu1", "calculate_iv_taxes", {"revenue": 2400})]),
        FakeResponse([text_block("Štai jūsų mokesčiai.")]),
    ])
    c, calc = client(agent_client=fake)

    r = c.post("/internal/chat", json={"message": "Kiek mokesčių sumoku nuo 2400 € IV pajamų?"})

    assert r.status_code == 200
    body = r.json()
    assert "Štai jūsų mokesčiai." in body["reply"]
    assert "458,79" in body["reply"] or "458.79" in body["reply"]
    assert len(calc.compare_calls) == 1
    assert "messages" in body
    # item 5: the response's own stop_reason - lets run_eval.py --via-endpoint reconstruct the
    # SAME response_complete check the direct run_agent_loop() call path gets from
    # AgentLoopResult.stop_reason, which the HTTP response otherwise has no way to carry.
    assert body["stop_reason"] == "end_turn"


# --- savings-only ------------------------------------------------------------------------------

def test_savings_only_calls_calculate_savings_plan_with_user_stated_income():
    savings_input = {
        "goal": {"type": "OTHER", "mode": "YEARS", "amount": 10000, "years": 5},
        "net_monthly_income": 1500,
    }
    fake = FakeAgentClient([
        FakeResponse([tool_use_block("tu1", "calculate_savings_plan", savings_input)]),
        FakeResponse([text_block("Štai jūsų taupymo planas.")]),
    ])
    c, calc = client(agent_client=fake)

    r = c.post("/internal/chat", json={"message": "Noriu sutaupyti 10000 € per 5 metus, gaunu 1500 € grynaisiais"})

    assert r.status_code == 200
    body = r.json()
    assert "Štai jūsų taupymo planas." in body["reply"]
    assert len(calc.savings_calls) == 1
    assert calc.savings_calls[0][1] == 1500  # net_monthly_income passed through


# --- combined (tax -> savings via tax_calc_id) --------------------------------------------------

class _CombinedScriptMessages:
    """Unlike FakeMessages' static script, the savings call's `tax_calc_id` is a fresh UUID
    minted by the FIRST tool call - this reads it back out of the tool_result the model would
    actually see, instead of a hardcoded placeholder that could never really match."""

    def __init__(self):
        self.calls = []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        if len(self.calls) == 1:
            return FakeResponse([tool_use_block("tu1", "calculate_iv_taxes", {"revenue": 2400})])
        if len(self.calls) == 2:
            tool_result_block = kwargs["messages"][-1]["content"][0]
            tax_result = json.loads(tool_result_block["content"])
            return FakeResponse([tool_use_block(
                "tu2", "calculate_savings_plan",
                {"goal": {"type": "OTHER", "mode": "YEARS", "amount": 5000, "years": 3},
                 "tax_calc_id": tax_result["tax_calc_id"]})])
        return FakeResponse([text_block("Apskaičiuota pagal jūsų IV pajamas.")])


class _CombinedScriptClient:
    def __init__(self):
        self.messages = _CombinedScriptMessages()


def test_combined_tax_then_savings_uses_tax_calc_id_across_turns():
    fake = _CombinedScriptClient()
    c, calc = client(agent_client=fake)

    r = c.post("/internal/chat", json={
        "message": "Gaunu 2400 € IV pajamų, noriu sutaupyti 5000 € per 3 metus pagal grynąsias pajamas",
    })

    assert r.status_code == 200
    body = r.json()
    assert "Apskaičiuota pagal jūsų IV pajamas." in body["reply"]
    assert len(calc.compare_calls) == 1
    assert len(calc.savings_calls) == 1
    # origin TAX_CALC income flowed from the stored tax result, not a literal "placeholder".
    assert calc.savings_calls[0][1] == Decimal("458.79")


# --- missing income ----------------------------------------------------------------------------

def test_missing_income_still_computes_a_contribution_and_invites_income_not_a_clarifying_question():
    """Owner decision, 2026-11-17 (first real staging walkthrough): a goal with no income
    mentioned at all ("Noriu sutaupyti 5000 € per 3 metus.") used to short-circuit into a
    clarifying question with NO calculation at all. It must now still call the savings tool
    (income simply omitted, never guessed) and return a computed monthly contribution plus the
    deterministic (Java-sourced) invitation to provide income - the model's own free text here is
    just an intro, never where the contribution number comes from."""
    fake = FakeAgentClient([
        FakeResponse([tool_use_block(
            "tu1", "calculate_savings_plan",
            {"goal": {"type": "OTHER", "mode": "YEARS", "amount": 5000, "years": 3}})]),
        FakeResponse([text_block("Štai jūsų taupymo planas.")]),
    ])
    c, calc = client(agent_client=fake)

    r = c.post("/internal/chat", json={"message": "Noriu sutaupyti 5000 € per 3 metus"})

    assert r.status_code == 200
    body = r.json()
    assert "100,00" in body["reply"]  # the computed monthly_contribution, never withheld
    assert "Jei norite sužinoti, ar tai telpa į biudžetą, nurodykite savo pajamas." in body["reply"]
    assert "laisvų pinigų" not in body["reply"].lower()  # no free-cash/fit line - unknown, not 0%
    assert len(calc.savings_calls) == 1
    assert calc.savings_calls[0][1] is None  # net_monthly_income omitted, never guessed


# --- daily limit ---------------------------------------------------------------------------

def test_anonymous_daily_cap_returns_a_friendly_login_invite_without_calling_the_model():
    fake = FakeAgentClient([FakeResponse([text_block("never reached")])])
    limiter = ChatLimiter(anonymous_daily_cap=0, tier_daily_caps={"FREE": 100, "PREMIUM": 500})
    c, _ = client(agent_client=fake, chat_limiter=limiter)

    r = c.post("/internal/chat", json={"message": "Labas"})

    assert r.status_code == 200
    body = r.json()
    assert body["limited"] is True
    assert "prisijunk" in body["reply"].lower()
    assert fake.messages.calls == []


def test_logged_in_daily_cap_returns_a_friendly_message_without_a_login_invite():
    fake = FakeAgentClient([FakeResponse([text_block("never reached")])])
    limiter = ChatLimiter(anonymous_daily_cap=100, tier_daily_caps={"FREE": 0, "PREMIUM": 500})
    c, _ = client(agent_client=fake, chat_limiter=limiter)

    r = c.post("/internal/chat", json={"message": "Labas", "tier": "FREE", "key": "user-1"})

    assert r.status_code == 200
    body = r.json()
    assert body["limited"] is True
    assert "prisijunk" not in body["reply"].lower()
    assert fake.messages.calls == []


# --- Claude API error ------------------------------------------------------------------------

def test_claude_api_error_returns_a_friendly_lithuanian_reply_not_a_stack_trace():
    fake_request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    fake = FakeAgentClient(error=APIConnectionError(request=fake_request))
    c, _ = client(agent_client=fake)

    r = c.post("/internal/chat", json={"message": "Labas"})

    assert r.status_code == 200
    body = r.json()
    assert "APIConnectionError" not in body["reply"]
    assert "Traceback" not in body["reply"]
    assert body["reply"]  # non-empty, friendly text
    assert "messages" not in body  # nothing successful to persist as history


def test_claude_api_error_reply_says_retry_or_start_over_and_logs_error_level_with_request_id(caplog):
    """Owner decision, 2026-11-17 (real crash via a thinking-block history bug, see
    `_append_history_turn`'s own docstring): the exact user-facing text must tell them what to do
    next, and the server log must be findable by `request_id` at ERROR level, not just whatever
    `logger.exception`'s default happened to be."""
    import logging

    fake_request = httpx.Request("POST", "https://api.anthropic.com/v1/messages")
    fake = FakeAgentClient(error=APIConnectionError(request=fake_request))
    c, _ = client(agent_client=fake)

    with caplog.at_level(logging.ERROR, logger="app.main"):
        r = c.post("/internal/chat", json={"message": "Labas"})

    assert r.status_code == 200
    assert r.json()["reply"] == "Įvyko klaida. Pabandykite dar kartą arba pradėkite naują pokalbį."
    error_records = [rec for rec in caplog.records if rec.levelno == logging.ERROR]
    assert len(error_records) == 1
    assert error_records[0].request_id  # non-empty, present on the record


# --- thinking blocks never replayed across HTTP turns (owner decision 7 fix, 2026-11-17) --------

class _ThinkingScriptMessages:
    """Every turn's fake model response carries a `thinking` block (Claude Haiku 5.5's adaptive
    thinking is on by default) alongside its final text - no tool calls, so each HTTP turn makes
    exactly one `create()` call."""

    def __init__(self):
        self.calls = []

    def create(self, **kwargs):
        # `kwargs["messages"]` is the SAME list `run_agent_loop` keeps mutating (appending this
        # very response) after `create()` returns - snapshot it now, or inspecting `self.calls`
        # later would see today's OWN freshly-appended response too, not just what was SENT.
        self.calls.append({**kwargs, "messages": list(kwargs["messages"])})
        i = len(self.calls)
        return FakeResponse([thinking_block(signature=f"sig-{i}"), text_block(f"Atsakymas {i}.")])


class _ThinkingScriptClient:
    def __init__(self):
        self.messages = _ThinkingScriptMessages()


def test_long_conversation_with_thinking_blocks_never_replays_them_across_http_turns():
    """Owner decision 7 fix, 2026-11-17 (real crash: `anthropic.BadRequestError` 400, "Invalid
    `signature` in `thinking` block... Content that preceded this block when it was created is
    missing from this request, starting at messages.0.") - drives 12 full HTTP turns through
    /internal/chat (more than MAX_HISTORY_MESSAGES=20's 10-turn-pair capacity, so truncation
    actually kicks in), each turn's fake model returning a `thinking` block. The history sent to
    the API on every turn after the first must carry NONE of the prior turns' thinking blocks
    (every persisted message is plain text, never a block list), and after truncation the first
    message must still be role "user", never "assistant"."""
    fake = _ThinkingScriptClient()
    c, _ = client(agent_client=fake)

    history = None
    for i in range(12):
        r = c.post("/internal/chat", json={"message": f"Žinutė {i}.", "history": history})
        assert r.status_code == 200
        history = r.json()["messages"]

    assert len(history) == 20  # truncated to MAX_HISTORY_MESSAGES, not 24
    assert history[0]["role"] == "user"  # truncation never leaves "assistant" first
    for msg in history:
        assert isinstance(msg["content"], str)  # never a block list - see _append_history_turn

    last_sent_messages = fake.messages.calls[-1]["messages"]
    assert last_sent_messages[0]["role"] == "user"
    for msg in last_sent_messages:
        # Every message sent to the API, except the live model's OWN response within this same
        # call (not part of the outgoing request), is plain-text history or the new user message -
        # never a `thinking`/`redacted_thinking` block replayed from an earlier HTTP turn.
        assert isinstance(msg["content"], str)


def test_unconfigured_agent_client_returns_a_friendly_reply_not_a_crash():
    c, _ = client(agent_client=None)  # no ANTHROPIC_API_KEY in this test env either

    r = c.post("/internal/chat", json={"message": "Labas"})

    assert r.status_code == 200
    assert "ANTHROPIC_API_KEY" in r.json()["reply"]


# --- flag false/true --------------------------------------------------------------------------

def test_flag_false_keeps_the_b1_stub_behavior_byte_for_byte():
    """Same assertion shape as test_internal_chat.py's own stub-path test - proves the flag
    actually gates behavior, not just that the stub still has its own passing tests."""
    fake = FakeAgentClient([FakeResponse([text_block("should never be called")])])
    calc = FakeCalc()
    extractor = SimpleNamespace(extract=lambda text: Extraction(revenue=Decimal("2400")))
    app = create_app(extractor=extractor, calculator=calc, agent_client=fake, agent_chat_enabled=False)
    c = TestClient(app)

    r = c.post("/internal/chat", json={"message": "Gavau 2400 €"})

    assert r.status_code == 200
    body = r.json()
    assert body["reply"] == "ack"
    assert "tool_result" in body
    assert fake.messages.calls == []  # the agent path was never touched


def test_flag_true_uses_the_real_agent_loop_instead_of_the_stub():
    fake = FakeAgentClient([FakeResponse([text_block("Agento atsakymas.")])])
    c, _ = client(agent_client=fake)

    r = c.post("/internal/chat", json={"message": "Labas"})

    assert r.status_code == 200
    body = r.json()
    assert body["reply"] == "Agento atsakymas."
    assert "tool_result" not in body
    assert len(fake.messages.calls) == 1


# --- history round-trip ------------------------------------------------------------------------

def test_history_from_the_request_is_prepended_and_the_new_messages_are_returned_for_storage():
    fake = FakeAgentClient([FakeResponse([text_block("Antras atsakymas.")])])
    c, _ = client(agent_client=fake)
    prior_history = [
        {"role": "user", "content": "Pirma žinutė"},
        {"role": "assistant", "content": [{"type": "text", "text": "Pirmas atsakymas."}]},
    ]

    r = c.post("/internal/chat", json={"message": "Antra žinutė", "history": prior_history})

    assert r.status_code == 200
    sent_messages = fake.messages.calls[0]["messages"]
    assert sent_messages[0] == prior_history[0]
    assert sent_messages[1] == prior_history[1]
    assert sent_messages[2]["content"] == "Antra žinutė"
    # Returned for Java to persist - includes the prepended history plus this turn's new messages.
    assert len(r.json()["messages"]) == 4


def test_usage_is_logged_once_per_message_to_stdout(capsys):
    fake = FakeAgentClient([FakeResponse([text_block("ok")])])
    c, _ = client(agent_client=fake)

    c.post("/internal/chat", json={"message": "Labas"})

    out = capsys.readouterr().out
    assert '"cost_micros"' in out
    assert '"tool_call_count": 0' in out


def test_a_truncated_final_answer_reports_its_real_stop_reason_not_end_turn():
    fake = FakeAgentClient([FakeResponse([text_block("nupjau")], stop_reason="max_tokens")])
    c, _ = client(agent_client=fake)

    r = c.post("/internal/chat", json={"message": "Labas"})

    assert r.json()["stop_reason"] == "max_tokens"
