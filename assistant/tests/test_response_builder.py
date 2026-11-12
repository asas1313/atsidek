"""AC-B4-1/AC-B4-2: `response_builder.build_response` is a deterministic template, not an
LLM-output test - every assertion here is about section ORDER and EXACT numeric equality given
fixed, hand-built `tool_calls` inputs (the same shape `agent_loop.AgentLoopResult.tool_calls`
produces), never about LLM-generated prose.
"""
from app.response_builder import build_response, pick_cta_feature


def tax_call(call_id="tu_tax", is_error=False, result=None):
    return {
        "id": call_id,
        "name": "calculate_iv_taxes",
        "input": {},
        "result": result,
        "error": None if not is_error else "boom",
        "is_error": is_error,
    }


def savings_call(call_id="tu_sav", is_error=False, result=None):
    return {
        "id": call_id,
        "name": "calculate_savings_plan",
        "input": {},
        "result": result,
        "error": None if not is_error else "boom",
        "is_error": is_error,
    }


TAX_RESULT = {
    "tax_calc_id": "abc123",
    "method": "ACTUAL",
    "total_tax_per_year": 1234.56,
    "monthly_tax_setaside": 102.88,
    "net_monthly_income": 987.65,
}

TAX_RESULT_FLAT = {
    "tax_calc_id": "abc124",
    "method": "FLAT_30",
    "total_tax_per_year": 2000,
    "monthly_tax_setaside": 166.67,
    "net_monthly_income": 1500,
}

ASSUMPTIONS = [
    {
        "field": "monthly_expenses",
        "value": 650.0,
        "origin": "STATISTICS",
        "source": "Sodra 2025",
        "source_date": "2025-01-01",
        "is_illustrative": True,
    },
    {
        "field": "annual_return_pct",
        "value": 3.5,
        "origin": "ASSUMPTION",
        "source": None,
        "source_date": None,
        "is_illustrative": None,
    },
]

SAVINGS_RESULT = {
    "tier": "FREE",
    "core": {
        "monthly_contribution": 200.0,
        "free_cash_monthly": 400.0,
        "share_of_free_cash_pct": 50.0,
        "fits": True,
        "general_tip": "Lieka rezervas netikėtoms išlaidoms.",
        "assumptions": ASSUMPTIONS,
        "disclaimer": "Šis skaičiavimas yra informacinio pobūdžio ir nėra finansinė konsultacija.",
    },
    "locked_features": ["SAVINGS_MULTI_GOAL", "SAVINGS_IV_LINK"],
    "income_origin": "TAX_CALC",
}

SAVINGS_RESULT_NO_ASSUMPTIONS_NO_LOCKS = {
    "tier": "PREMIUM",
    "core": {
        "monthly_contribution": 300.0,
        "free_cash_monthly": 500.0,
        "share_of_free_cash_pct": 60.0,
        "fits": True,
        "general_tip": "Viskas gerai.",
        "assumptions": [],
        "disclaimer": "Šis skaičiavimas yra informacinio pobūdžio ir nėra finansinė konsultacija.",
    },
    "locked_features": [],
    "income_origin": "USER",
}


# --- AC-B4-1: section ordering + exact numeric equality ----------------------------------------

def test_full_response_section_ordering_taxes_before_free_cash_before_goal_before_assumptions_before_disclaimer_before_cta():
    tool_calls = [tax_call(result=TAX_RESULT), savings_call(result=SAVINGS_RESULT)]

    text = build_response(tool_calls, None)

    idx_taxes = text.index("1 234,56")
    idx_free_cash = text.index("400")
    idx_goal = text.index("200")
    idx_assumptions = text.index("Mėnesio išlaidos")
    idx_disclaimer = text.index(SAVINGS_RESULT["core"]["disclaimer"])
    idx_cta = text.index("Tavo pajamos svyruoja")

    assert idx_taxes < idx_free_cash < idx_goal < idx_assumptions < idx_disclaimer < idx_cta


def test_tax_section_numbers_match_tool_result_exactly():
    tool_calls = [tax_call(result=TAX_RESULT)]

    text = build_response(tool_calls, None)

    assert "faktinės išlaidos" in text
    assert "1 234,56" in text  # total_tax_per_year
    assert "102,88" in text  # monthly_tax_setaside
    assert "987,65" in text  # net_monthly_income


def test_tax_section_flat_30_method_label():
    tool_calls = [tax_call(result=TAX_RESULT_FLAT)]

    text = build_response(tool_calls, None)

    assert "30 % išlaidų norma" in text


def test_goal_and_free_cash_section_numbers_match_tool_result_exactly():
    tool_calls = [savings_call(result=SAVINGS_RESULT)]

    text = build_response(tool_calls, None)

    assert "400,00" in text  # free_cash_monthly
    assert "200,00" in text  # monthly_contribution
    assert "50,0%" in text  # share_of_free_cash_pct, NOT multiplied again
    assert SAVINGS_RESULT["core"]["general_tip"] in text


def test_assumptions_section_renders_one_line_per_entry_with_origin_label():
    tool_calls = [savings_call(result=SAVINGS_RESULT)]

    text = build_response(tool_calls, None)

    assert "Mėnesio išlaidos" in text
    assert "650,00" in text
    assert "Statistika: Sodra 2025" in text
    assert "Metinė grąža" in text
    assert "3,5%" in text
    assert "Prielaida" in text


def test_disclaimer_rendered_verbatim():
    tool_calls = [savings_call(result=SAVINGS_RESULT)]

    text = build_response(tool_calls, None)

    assert SAVINGS_RESULT["core"]["disclaimer"] in text


def test_only_taxes_no_savings_call_renders_taxes_section_only_no_savings_sections():
    tool_calls = [tax_call(result=TAX_RESULT)]

    text = build_response(tool_calls, None)

    assert "1 234,56" in text
    assert "Mėnesio išlaidos" not in text
    assert SAVINGS_RESULT["core"]["disclaimer"] not in text


def test_only_savings_no_tax_call_renders_savings_sections_only_no_tax_section():
    tool_calls = [savings_call(result=SAVINGS_RESULT)]

    text = build_response(tool_calls, None)

    assert "faktinės išlaidos" not in text
    assert "30 % išlaidų norma" not in text
    assert "400,00" in text


def test_neither_tool_called_returns_final_text_alone():
    assert build_response([], "Sveika! Kuo galiu padėti?") == "Sveika! Kuo galiu padėti?"


def test_neither_tool_called_and_no_final_text_returns_empty_string():
    assert build_response([], None) == ""


def test_final_text_precedes_deterministic_sections_when_both_present():
    tool_calls = [tax_call(result=TAX_RESULT)]
    final_text = "Štai tavo mokesčių suvestinė:"

    text = build_response(tool_calls, final_text)

    assert text.index(final_text) < text.index("1 234,56")


def test_is_error_entry_is_ignored_last_successful_call_wins():
    tool_calls = [
        tax_call(call_id="tu1", result=TAX_RESULT),
        tax_call(call_id="tu2", is_error=True, result=None),
    ]

    text = build_response(tool_calls, None)

    assert "1 234,56" in text  # the successful result, not the later failed one


def test_last_successful_call_wins_when_multiple_successes_present():
    tool_calls = [
        tax_call(call_id="tu1", result=TAX_RESULT),
        tax_call(call_id="tu2", result=TAX_RESULT_FLAT),
    ]

    text = build_response(tool_calls, None)

    assert "30 % išlaidų norma" in text
    assert "faktinės išlaidos" not in text


def test_empty_assumptions_list_omits_assumptions_section():
    tool_calls = [savings_call(result=SAVINGS_RESULT_NO_ASSUMPTIONS_NO_LOCKS)]

    text = build_response(tool_calls, None)

    assert "Mėnesio išlaidos" not in text
    assert "Metinė grąža" not in text


def test_empty_locked_features_omits_cta_section():
    tool_calls = [savings_call(result=SAVINGS_RESULT_NO_ASSUMPTIONS_NO_LOCKS)]

    text = build_response(tool_calls, None)

    assert "prisijung" not in text.lower()


# --- AC-B4-2: pick_cta_feature ------------------------------------------------------------------

def test_pick_cta_feature_empty_list_returns_none():
    assert pick_cta_feature([]) is None


def test_pick_cta_feature_preferred_feature_wins_regardless_of_position():
    assert pick_cta_feature(["SAVINGS_MULTI_GOAL", "SAVINGS_IV_LINK", "SAVINGS_SCENARIOS"]) == "SAVINGS_IV_LINK"
    assert pick_cta_feature(["SAVINGS_IV_LINK", "SAVINGS_MULTI_GOAL"]) == "SAVINGS_IV_LINK"


def test_pick_cta_feature_without_preferred_feature_first_element_wins():
    assert pick_cta_feature(["SAVINGS_MULTI_GOAL", "SAVINGS_SCENARIOS"]) == "SAVINGS_MULTI_GOAL"
    assert pick_cta_feature(["SAVINGS_SCENARIOS"]) == "SAVINGS_SCENARIOS"


def test_cta_section_renders_exactly_one_sentence_for_preferred_feature():
    tool_calls = [savings_call(result=SAVINGS_RESULT)]

    text = build_response(tool_calls, None)

    assert text.count("prisijung") >= 1
    # Only the preferred feature's sentence appears, not the other locked feature's sentence.
    assert "daugiau nei vieną taupymo tikslą" not in text
