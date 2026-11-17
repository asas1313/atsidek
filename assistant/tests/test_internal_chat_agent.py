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


# --- missing income --------------------------------------------------------------------------

def test_missing_income_surfaces_as_a_clarifying_question_not_a_crash():
    fake = FakeAgentClient([
        FakeResponse([tool_use_block(
            "tu1", "calculate_savings_plan",
            {"goal": {"type": "OTHER", "mode": "YEARS", "amount": 5000, "years": 3}})]),
        FakeResponse([text_block("Kokios jūsų grynosios mėnesio pajamos?")]),
    ])
    c, calc = client(agent_client=fake)

    r = c.post("/internal/chat", json={"message": "Noriu sutaupyti 5000 € per 3 metus"})

    assert r.status_code == 200
    body = r.json()
    assert "Kokios jūsų grynosios mėnesio pajamos?" in body["reply"]
    assert len(calc.savings_calls) == 0  # resolve_income raised before calling Java at all


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
    assert '"cost_cents"' in out
    assert '"tool_call_count": 0' in out


def test_a_truncated_final_answer_reports_its_real_stop_reason_not_end_turn():
    fake = FakeAgentClient([FakeResponse([text_block("nupjau")], stop_reason="max_tokens")])
    c, _ = client(agent_client=fake)

    r = c.post("/internal/chat", json={"message": "Labas"})

    assert r.json()["stop_reason"] == "max_tokens"
