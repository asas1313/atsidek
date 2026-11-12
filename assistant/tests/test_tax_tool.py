"""calculate_iv_taxes tool (B1, AC-B1-1..4).

Fake Calculator (matching the real `Calculator` Protocol in calculator.py) throughout - no live
HTTP, no live Claude tool-call round trip (there is no real agent loop until B3; see plan).
"""
from decimal import Decimal

import pytest
from pydantic import ValidationError

from app.request_context import RequestContext
from app.tax_tool import TOOL_DEF, TOOL_NAME, CalculateIvTaxesInput, make_calculate_iv_taxes_tool


def result(total="4875.50", monthly="406.29", net_monthly="2093.71", method="FLAT_30"):
    d = Decimal
    return {"ruleYear": 2026, "method": method, "revenue": d("30000.00"), "expenses": d("9000.00"),
            "taxableIncome": d("21000.00"), "sodraBase": d("18900.00"), "vsd": d("2366.28"),
            "psd": d("1319.22"), "pensionAccumulation": d(0), "gpmBase": d("21000.00"), "gpm": d("1190.00"),
            "extraGpm": d(0), "total": d(total), "monthlyReserve": d(monthly), "effectiveRate": d("0.2322"),
            "sodraCeilingHit": False, "netMonthlyIncome": d(net_monthly)}


def compare_json(better_total="4875.50", better_monthly="406.29", better_net="2093.71"):
    better = result(better_total, better_monthly, better_net, "FLAT_30")
    return {"flat": better, "actual": result("7282.55", "606.88", "1893.12", "ACTUAL"), "better": better}


class FakeCalc:
    """Mirrors `app.calculator.Calculator`'s Protocol, including the optional context_token."""

    def __init__(self, response=None):
        self.response = response or compare_json()
        self.calls = []

    def compare(self, revenue, actual_expenses, other_income, pension_accumulation, psd_min_months,
                context_token=None):
        self.calls.append((revenue, actual_expenses, other_income, pension_accumulation, psd_min_months,
                            context_token))
        return self.response


def test_calls_atsidek_api_with_expected_call_shape():
    """AC-B1-1: call shape (revenue/expenses/other/pillar2/psd_months) matches today's
    AtsidekApi.compare()."""
    calc = FakeCalc()
    handler = make_calculate_iv_taxes_tool(calc)
    ctx = RequestContext(user_id=None, tier="anonymous")

    handler({
        "revenue": Decimal("2400"),
        "actual_expenses": Decimal("300"),
        "other_income": Decimal("100"),
        "pension_accumulation": True,
        "psd_min_months": 7,
    }, ctx, None)

    assert calc.calls == [(Decimal("2400"), Decimal("300"), Decimal("100"), True, 7, None)]


def test_defaults_match_todays_asistentas_defaults_when_optional_fields_are_absent():
    calc = FakeCalc()
    handler = make_calculate_iv_taxes_tool(calc)
    ctx = RequestContext(user_id=None, tier="anonymous")

    handler({"revenue": Decimal("1000")}, ctx, None)

    assert calc.calls == [(Decimal("1000"), None, None, False, 12, None)]


def test_surfaces_java_computed_net_monthly_income_without_any_arithmetic_of_its_own():
    """AC-B1-2: the tool surfaces exactly the Java-returned netMonthlyIncome, no recomputation."""
    calc = FakeCalc(compare_json(better_net="9999.99"))
    handler = make_calculate_iv_taxes_tool(calc)
    ctx = RequestContext(user_id=None, tier="anonymous")

    out = handler({"revenue": Decimal("30000")}, ctx, None)

    assert out["net_monthly_income"] == Decimal("9999.99")


def test_result_stored_under_tax_calc_id_resolves_to_the_same_entry_on_second_lookup():
    """AC-B1-3: opaque tax_calc_id round-trips to the same stored RequestContext entry, and the
    raw Java response (incl. internal field names like sodraBase/gpmBase) does not leak into the
    tool-result payload returned to the model."""
    java_response = compare_json()
    calc = FakeCalc(java_response)
    handler = make_calculate_iv_taxes_tool(calc)
    ctx = RequestContext(user_id=None, tier="anonymous")

    out = handler({"revenue": Decimal("30000")}, ctx, None)

    assert "tax_calc_id" in out
    assert ctx.get_result(out["tax_calc_id"]) == java_response
    # Antra paieška tuo pačiu id - tas pats įrašas.
    assert ctx.get_result(out["tax_calc_id"]) is ctx.get_result(out["tax_calc_id"])
    # Vidiniai Java laukų vardai nenuteka į modeliui grąžinamą payload'ą.
    assert "sodraBase" not in out and "gpmBase" not in out and "flat" not in out and "actual" not in out


def test_context_token_is_forwarded_unmodified_and_never_inspected():
    """AC-B1-4 (Python half): the tool handler forwards the token string unchanged, performing no
    verification/decoding of its own."""
    calc = FakeCalc()
    handler = make_calculate_iv_taxes_tool(calc)
    ctx = RequestContext(user_id="some-user", tier="PREMIUM")
    token = "eyJ1c2VySWQiOiJ4In0.deadbeef"

    handler({"revenue": Decimal("1000")}, ctx, token)

    assert calc.calls[0][-1] is token


def test_missing_context_token_is_also_fine_and_forwarded_as_none():
    calc = FakeCalc()
    handler = make_calculate_iv_taxes_tool(calc)
    ctx = RequestContext(user_id=None, tier="anonymous")

    handler({"revenue": Decimal("1000")}, ctx)

    assert calc.calls[0][-1] is None


def test_missing_revenue_is_rejected_by_pydantic_before_calling_java():
    with pytest.raises(ValidationError) as exc:
        CalculateIvTaxesInput.model_validate({})
    assert any("revenue" in str(e["loc"]) for e in exc.value.errors())


def test_unknown_field_is_rejected():
    with pytest.raises(ValidationError):
        CalculateIvTaxesInput.model_validate({"revenue": 1000, "bogus_field": 1})


def test_tool_name_constant_is_calculate_iv_taxes():
    assert TOOL_NAME == "calculate_iv_taxes"


def test_tool_def_name_matches_tool_name_constant():
    assert TOOL_DEF["name"] == TOOL_NAME


def test_tool_def_input_schema_field_names_match_the_pydantic_model_exactly():
    """Cheap regression guard (B3 task list) against TOOL_DEF's hand-written input_schema
    silently drifting from CalculateIvTaxesInput's real, Pydantic-validated field set."""
    schema_fields = set(TOOL_DEF["input_schema"]["properties"].keys())
    model_fields = set(CalculateIvTaxesInput.model_fields.keys())
    assert schema_fields == model_fields


def test_tool_def_required_lists_only_the_field_with_no_default():
    assert TOOL_DEF["input_schema"]["required"] == ["revenue"]
