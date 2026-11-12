"""calculate_savings_plan tool (B2, AC-B2-1..4).

AC-B2-1's tests (schema shape/mutual-exclusivity) are DONE and green — the Pydantic draft in
app/savings_tool.py is complete. Everything below that line is a RED skeleton: it documents what
each AC-B2 row requires, using a FakeSavingsApi shaped like the `AtsidekApi.savings_plan()`
method the pairing session still needs to write (mirroring the existing `AtsidekApi.compare()`
in calculator.py), but `make_calculate_savings_plan_tool` itself intentionally raises
NotImplementedError until the handler is written together with the owner — see
docs/specs/taupymo-tikslai/B2-pairing-notes.md.
"""
from decimal import Decimal

import pytest
from pydantic import ValidationError

from app.request_context import RequestContext, UnknownResultError
from app.savings_tool import TOOL_NAME, CalculateSavingsPlanInput, make_calculate_savings_plan_tool


def goal(amount="50000"):
    return {"type": "OTHER", "mode": "YEARS", "amount": Decimal(amount), "years": 10}


class FakeSavingsApi:
    """Shaped like the AtsidekApi.savings_plan() method that still needs to be written (mirror
    of AtsidekApi.compare()) — NOT a real client, just what the pairing session's handler will
    call into."""

    def __init__(self, response=None):
        self.response = response or {
            "tier": "FREE", "core": {"monthly_contribution": "100.00"}, "locked_features": [],
        }
        self.calls = []

    def savings_plan(self, goal, net_monthly_income, monthly_tax_setaside, monthly_expenses,
                      household, assumptions, context_token=None):
        self.calls.append((goal, net_monthly_income, monthly_tax_setaside, monthly_expenses,
                            household, assumptions, context_token))
        return self.response


# --- AC-B2-1: schema shape (DONE, green) -----------------------------------------------------

def test_tool_name_constant_is_calculate_savings_plan():
    assert TOOL_NAME == "calculate_savings_plan"


def test_rejects_payload_with_both_tax_calc_id_and_net_monthly_income():
    with pytest.raises(ValidationError):
        CalculateSavingsPlanInput.model_validate({
            "goal": goal(), "tax_calc_id": "abc", "net_monthly_income": Decimal("1000"),
        })


def test_accepts_tax_calc_id_alone():
    parsed = CalculateSavingsPlanInput.model_validate({"goal": goal(), "tax_calc_id": "abc"})
    assert parsed.tax_calc_id == "abc" and parsed.net_monthly_income is None


def test_accepts_net_monthly_income_alone():
    parsed = CalculateSavingsPlanInput.model_validate(
        {"goal": goal(), "net_monthly_income": Decimal("1000")})
    assert parsed.net_monthly_income == Decimal("1000") and parsed.tax_calc_id is None


def test_accepts_neither_tax_calc_id_nor_net_monthly_income_at_schema_level():
    """AC-B2-3's "need income" error is a HANDLER-level tool error, not a schema rejection —
    the model may legitimately omit both and let the handler decide (e.g. ask the user)."""
    parsed = CalculateSavingsPlanInput.model_validate({"goal": goal()})
    assert parsed.tax_calc_id is None and parsed.net_monthly_income is None


def test_unknown_field_is_rejected():
    with pytest.raises(ValidationError):
        CalculateSavingsPlanInput.model_validate({"goal": goal(), "bogus_field": 1})


# --- AC-B2-2: resolve from tax_calc_id (RED — pairing exercise) ------------------------------

def test_known_tax_calc_id_injects_the_exact_b1_stored_values():
    """A prior calculate_iv_taxes call stored its raw Java response under some id; the savings
    tool must pull net_monthly_income/monthly_tax_setaside from THAT exact stored entry (not
    recompute, not re-call Java) and use them to call savings_plan()."""
    ctx = RequestContext(user_id=None, tier="anonymous")
    stored_tax_result = {"better": {"netMonthlyIncome": Decimal("2093.71"), "monthlyReserve": Decimal("406.29")}}
    tax_calc_id = ctx.store_result(stored_tax_result)
    api = FakeSavingsApi()

    handler = make_calculate_savings_plan_tool(api)  # currently: NotImplementedError
    handler({"goal": goal(), "tax_calc_id": tax_calc_id}, ctx, None)

    net_income, tax_setaside = api.calls[0][1], api.calls[0][2]
    assert net_income == Decimal("2093.71")
    assert tax_setaside == Decimal("406.29")


def test_unknown_tax_calc_id_is_a_tool_error_not_a_crashed_turn():
    """Mirrors ToolRegistry's UnknownToolError contract: a bad id must become a defined,
    catchable error (UnknownResultError, added ahead of B2 in request_context.py), never a bare
    KeyError that would kill the whole agent turn (B3's is_error contract)."""
    ctx = RequestContext(user_id=None, tier="anonymous")
    api = FakeSavingsApi()
    handler = make_calculate_savings_plan_tool(api)  # currently: NotImplementedError

    with pytest.raises(UnknownResultError):
        handler({"goal": goal(), "tax_calc_id": "does-not-exist"}, ctx, None)


# --- AC-B2-3: resolve from a user-stated figure, or refuse to guess (RED) --------------------

def test_user_stated_net_monthly_income_is_used_directly():
    ctx = RequestContext(user_id=None, tier="anonymous")
    api = FakeSavingsApi()
    handler = make_calculate_savings_plan_tool(api)  # currently: NotImplementedError

    handler({"goal": goal(), "net_monthly_income": Decimal("1500")}, ctx, None)

    assert api.calls[0][1] == Decimal("1500")


def test_neither_tax_calc_id_nor_net_monthly_income_is_a_tool_error_not_a_guess():
    """The handler must NEVER fall back to a stats-derived or fabricated income figure (PLAN.md
    principle, same rule B1/A3 already follow) — missing income is always an explicit error for
    the model/agent loop to handle (ask the user), never a silent guess."""
    ctx = RequestContext(user_id=None, tier="anonymous")
    api = FakeSavingsApi()
    handler = make_calculate_savings_plan_tool(api)  # currently: NotImplementedError

    with pytest.raises(Exception):  # exact error type is the pairing session's call
        handler({"goal": goal()}, ctx, None)

    assert api.calls == []  # Java must never be called with a fabricated/absent income.


# --- AC-B2-4: identity/token forwarding, tier-scoped disclosure (RED) ------------------------

def test_context_token_is_forwarded_unmodified_same_as_b1():
    ctx = RequestContext(user_id="some-user", tier="PREMIUM")
    api = FakeSavingsApi()
    handler = make_calculate_savings_plan_tool(api)  # currently: NotImplementedError
    token = "eyJ1c2VySWQiOiJ4In0.deadbeef"

    handler({"goal": goal(), "net_monthly_income": Decimal("1000")}, ctx, token)

    assert api.calls[0][-1] is token


def test_anonymous_caller_never_sees_a_premium_key_in_the_tool_result():
    """Reuses A4's existing anon/premium contract (SavingsPlanResponse: premium key absent, not
    null, for an anonymous caller) — the tool's returned payload to the model must preserve that,
    not invent a premium block of its own."""
    ctx = RequestContext(user_id=None, tier="anonymous")
    api = FakeSavingsApi(response={"tier": "FREE", "core": {}, "locked_features": ["SAVINGS_IV_LINK"]})
    handler = make_calculate_savings_plan_tool(api)  # currently: NotImplementedError

    out = handler({"goal": goal(), "net_monthly_income": Decimal("1000")}, ctx, None)

    assert "premium" not in out
