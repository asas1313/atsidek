"""AC-B4-1/AC-B4-2: `response_builder.build_response` is a deterministic template, not an
LLM-output test - every assertion here is about section ORDER and EXACT numeric equality given
fixed, hand-built `tool_calls` inputs (the same shape `agent_loop.AgentLoopResult.tool_calls`
produces), never about LLM-generated prose.
"""
from app.response_builder import (
    LOCKED_FEATURE_CTA,
    LOCKED_FEATURE_CTA_NEUTRAL_IV,
    LOGGED_IN_FREE_CTA,
    LOGGED_IN_FREE_CTA_NEUTRAL_IV,
    build_response,
    pick_cta_feature,
)


def tax_call(call_id="tu_tax", is_error=False, result=None):
    return {
        "id": call_id,
        "name": "calculate_iv_taxes",
        "input": {},
        "result": result,
        "error": None if not is_error else "boom",
        "is_error": is_error,
    }


def savings_call(call_id="tu_sav", is_error=False, result=None, input=None):
    return {
        "id": call_id,
        "name": "calculate_savings_plan",
        "input": input if input is not None else {},
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
        "needs_review": False,
    },
    {
        "field": "annual_return_pct",
        "value": 3.5,
        "origin": "ASSUMPTION",
        "source": None,
        "source_date": None,
        "is_illustrative": None,
        "needs_review": None,
    },
]

# Owner instruction (2026-11-16, stat_defaults sprendimas #1-2): ALL stat_defaults rows today are
# needs_review=true (V11 migration) - this is the REALISTIC fixture, unlike ASSUMPTIONS above
# (which models the future confirmed-source case).
ASSUMPTIONS_NEEDS_REVIEW = [
    {
        "field": "monthly_expenses",
        "value": 650.0,
        "origin": "STATISTICS",
        "source": "Įvertinta OECD skale nuo maketo reikšmės",
        "source_date": None,
        "is_illustrative": True,
        "needs_review": True,
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
    idx_cta = text.index("Jūsų pajamos svyruoja")

    assert idx_taxes < idx_free_cash < idx_goal < idx_assumptions < idx_disclaimer < idx_cta


def test_tax_section_numbers_match_tool_result_exactly():
    tool_calls = [tax_call(result=TAX_RESULT)]

    text = build_response(tool_calls, None)

    assert "faktinių išlaidų" in text
    assert "1 234,56" in text  # total_tax_per_year
    assert "102,88" in text  # monthly_tax_setaside
    assert "987,65" in text  # net_monthly_income


def test_tax_section_flat_30_method_label():
    tool_calls = [tax_call(result=TAX_RESULT_FLAT)]

    text = build_response(tool_calls, None)

    assert "30 % išlaidų normos" in text


# --- Owner instruction (2026-11-16, after eval a2dbaae's judge FAIL, item 2) --------------------

def test_flat_30_with_actual_expenses_comparison_adds_the_explanatory_sentence():
    tool_calls = [tax_call(result={
        **TAX_RESULT_FLAT,
        "flat_30_total_tax_per_year": 2000,
        "actual_expenses_total_tax_per_year": 3500,
    })]

    text = build_response(tool_calls, None)

    assert "Taikyta 30 % norma, nes ji palankesnė nei faktinės išlaidos" in text
    assert "2 000,00" in text
    assert "3 500,00" in text
    assert " vs " in text


def test_flat_30_without_an_actual_expenses_comparison_omits_the_explanatory_sentence():
    """TAX_RESULT_FLAT has no actual_expenses_total_tax_per_year key at all (the user never
    stated actual expenses) - the sentence must not appear."""
    tool_calls = [tax_call(result=TAX_RESULT_FLAT)]

    text = build_response(tool_calls, None)

    assert "Taikyta 30 % norma" not in text


def test_actual_method_with_a_comparison_value_present_still_omits_the_sentence():
    """The explanatory sentence is specifically about FLAT_30 being chosen - never add it when
    ACTUAL was the chosen method, even if a comparison value happens to be present."""
    tool_calls = [tax_call(result={
        **TAX_RESULT,
        "flat_30_total_tax_per_year": 5000,
        "actual_expenses_total_tax_per_year": 1234.56,
    })]

    text = build_response(tool_calls, None)

    assert "Taikyta 30 % norma" not in text


def test_goal_and_free_cash_section_numbers_match_tool_result_exactly():
    tool_calls = [savings_call(result=SAVINGS_RESULT)]

    text = build_response(tool_calls, None)

    assert "400,00" in text  # free_cash_monthly
    assert "200,00" in text  # monthly_contribution
    assert "50,0%" in text  # share_of_free_cash_pct, NOT multiplied again
    assert SAVINGS_RESULT["core"]["general_tip"] in text


def test_assumptions_section_renders_statistika_with_source_and_year_when_confirmed():
    """needs_review=false (a future, confirmed VDA import) renders "Statistika: <source>, <year>"."""
    tool_calls = [savings_call(result=SAVINGS_RESULT)]

    text = build_response(tool_calls, None)

    assert "Mėnesio išlaidos" in text
    assert "650,00" in text
    assert "Statistika: Sodra 2025, 2025" in text
    assert "Metinė grąža" in text
    assert "3,5%" in text
    assert "Prielaida" in text


def test_assumptions_section_renders_illustrative_assumption_not_statistika_when_needs_review():
    """Owner instruction (2026-11-16, stat_defaults sprendimas #1): a needs_review=true
    STATISTICS-origin value (the realistic case for every stat_defaults row today) must render as
    "Iliustracinė prielaida", NEVER "Statistika" - that label is reserved for confirmed values."""
    result = {**SAVINGS_RESULT, "core": {**SAVINGS_RESULT["core"], "assumptions": ASSUMPTIONS_NEEDS_REVIEW}}
    tool_calls = [savings_call(result=result)]

    text = build_response(tool_calls, None)

    assert "Mėnesio išlaidos" in text
    assert "Iliustracinė prielaida" in text
    assert "Statistika" not in text


def test_assumptions_section_treats_a_missing_needs_review_key_as_needing_review():
    """Conservative default: an entry with no needs_review key at all must still NOT be labeled
    "Statistika" - never assume confirmed just because the flag is absent."""
    entry = {k: v for k, v in ASSUMPTIONS_NEEDS_REVIEW[0].items() if k != "needs_review"}
    result = {**SAVINGS_RESULT, "core": {**SAVINGS_RESULT["core"], "assumptions": [entry]}}
    tool_calls = [savings_call(result=result)]

    text = build_response(tool_calls, None)

    assert "Iliustracinė prielaida" in text
    assert "Statistika" not in text


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

    assert "faktinių išlaidų" not in text
    assert "30 % išlaidų normos" not in text
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

    assert "30 % išlaidų normos" in text
    assert "faktinių išlaidų" not in text


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


# --- QA follow-up: empty inputs, defensive degradation, exact-number independence ---------------

def test_empty_tool_calls_and_empty_string_final_text_returns_empty_string():
    """Falsy-but-not-None `final_text` (e.g. "") must behave like `None` - still returns ""."""
    assert build_response([], "") == ""


def test_no_tool_calls_at_all_and_final_text_none_is_empty_string_not_none_or_crash():
    result = build_response([], None)
    assert result == ""
    assert result is not None


def test_last_successful_savings_call_wins_when_a_later_savings_call_fails():
    """AC-B4-1's 'last successful call wins, not last call overall', specifically for
    calculate_savings_plan (the existing coverage only exercised calculate_iv_taxes for this)."""
    tool_calls = [
        savings_call(call_id="s1", result=SAVINGS_RESULT),
        savings_call(call_id="s2", is_error=True, result=None),
    ]

    text = build_response(tool_calls, None)

    # The earlier, successful result's numbers are used - not a crash, not an empty savings
    # section from falling through to the later failed call.
    assert "400,00" in text  # free_cash_monthly from SAVINGS_RESULT
    assert "200,00" in text  # monthly_contribution from SAVINGS_RESULT


def test_mixed_tax_and_savings_calls_each_pick_their_own_last_successful_result_independently():
    """A failed LATER tax call must not blank out the savings section, and vice versa - the two
    tools' 'last successful' lookups are independent of each other."""
    tool_calls = [
        tax_call(call_id="t1", result=TAX_RESULT),
        savings_call(call_id="s1", result=SAVINGS_RESULT),
        tax_call(call_id="t2", is_error=True, result=None),
    ]

    text = build_response(tool_calls, None)

    assert "1 234,56" in text
    assert "400,00" in text


def test_final_text_numbers_are_not_mixed_into_or_used_for_deterministic_sections():
    """AC-B4-1: numbers come EXACTLY from tool results, never from `final_text` - a bogus number
    in the model's free text must pass through verbatim in its own place but must not replace or
    corrupt the tool-derived numeric section."""
    tool_calls = [tax_call(result=TAX_RESULT)]
    final_text = "Apytiksliai tai gali būti apie 999999 eur."

    text = build_response(tool_calls, final_text)

    assert final_text in text
    assert "1 234,56" in text  # the tool's real number, unaffected by the bogus one above
    assert "102,88" in text
    assert "987,65" in text


def test_negative_free_cash_monthly_renders_as_sane_negative_money_string():
    """Per `GoalsEngine`'s NO_FREE_CASH verdict, `free_cash_monthly` can be negative - must
    render as a normal signed amount, not raise or produce garbled output."""
    result = {
        "tier": "FREE",
        "core": {
            "monthly_contribution": 0.0,
            "free_cash_monthly": -50.5,
            "share_of_free_cash_pct": 0.0,
            "fits": False,
            "general_tip": "Pagal nurodytas pajamas ir išlaidas laisvų pinigų nelieka.",
            "assumptions": [],
            "disclaimer": "Šis skaičiavimas yra informacinio pobūdžio ir nėra finansinė konsultacija.",
        },
        "locked_features": [],
        "income_origin": "USER",
    }

    text = build_response([savings_call(result=result)], None)

    assert "-50,50 €" in text
    assert "0,00 €" in text


def test_zero_or_negative_free_cash_never_renders_a_misleading_zero_percent_share():
    """Owner instruction (2026-11-13, second B6 live-eval round): "0,0% laisvų pinigų" reads as
    "a comfortable 0% share of your free cash", not "there is no free cash at all" - must say
    plainly that there is no free cash and the contribution does not fit, for BOTH the exact-zero
    and negative free_cash_monthly cases."""
    for free_cash in ("0.0", "-50.5"):
        result = {
            "core": {
                "monthly_contribution": 0.0,
                "free_cash_monthly": float(free_cash),
                "share_of_free_cash_pct": 0.0,
                "fits": False,
                "general_tip": "Pagal nurodytas pajamas ir išlaidas laisvų pinigų nelieka.",
                "assumptions": [],
                "disclaimer": "Šis skaičiavimas yra informacinio pobūdžio ir nėra finansinė konsultacija.",
            },
            "locked_features": [],
        }

        text = build_response([savings_call(result=result)], None)

        assert "0,0% laisvų pinigų" not in text, free_cash
        assert "Laisvų pinigų nėra" in text, free_cash
        assert "netelpa" in text, free_cash


def test_positive_free_cash_still_renders_the_normal_percent_share_sentence():
    result = {
        "core": {
            "monthly_contribution": 100.0,
            "free_cash_monthly": 400.0,
            "share_of_free_cash_pct": 25.0,
            "fits": True,
            "general_tip": "Šis tikslas atrodo pasiekiamas.",
            "assumptions": [],
            "disclaimer": "disclaimer",
        },
        "locked_features": [],
    }

    text = build_response([savings_call(result=result)], None)

    assert "25,0% laisvų pinigų" in text
    assert "Laisvų pinigų nėra" not in text


def test_share_over_100_percent_renders_virsija_laisvus_pinigus_netelpa_not_a_percentage():
    """Owner instruction (2026-11-16, after B6 eval #11, item 6b): free cash IS positive here
    (400), but the contribution (500) exceeds it - share_of_free_cash_pct > 100 reads just as
    misleadingly as the free_cash_monthly<=0 case ("150,0% laisvų pinigų" is not informative) -
    must say plainly that it doesn't fit instead.

    Owner instruction (2026-11-16, after eval a2dbaae, item 4a): tightened to EXACTLY one
    sentence - "Įmoka viršija laisvus pinigus – netelpa." - Java's own general_tip (UNREALISTIC
    verdict's "viršija pusę"/"ilgesnį terminą" wording) is no longer appended here; it understates
    a >100% overflow and conflicts with item 4b's WHEN-mode guidance."""
    result = {
        "core": {
            "monthly_contribution": 500.0,
            "free_cash_monthly": 400.0,
            "share_of_free_cash_pct": 125.0,
            "fits": False,
            "general_tip": "tip",
            "assumptions": [],
            "disclaimer": "disclaimer",
        },
        "locked_features": [],
    }

    text = build_response([savings_call(result=result)], None)

    assert "Įmoka viršija laisvus pinigus – netelpa." in text
    assert "125,0%" not in text
    assert "Laisvų pinigų nėra" not in text  # distinct from the free_cash<=0 branch's wording
    assert "tip" not in text  # Java's general_tip no longer appended for this branch
    assert "viršija pusę" not in text
    assert "500,00" not in text  # no longer restates the contribution amount in this sentence


def test_share_exactly_100_percent_still_renders_the_normal_percent_sentence_not_the_overflow_one():
    """Boundary: exactly 100% is not OVER 100%, so the normal percent-share sentence still
    applies - only `> 100` triggers the "viršija" wording."""
    result = {
        "core": {
            "monthly_contribution": 400.0,
            "free_cash_monthly": 400.0,
            "share_of_free_cash_pct": 100.0,
            "fits": False,
            "general_tip": "tip",
            "assumptions": [],
            "disclaimer": "disclaimer",
        },
        "locked_features": [],
    }

    text = build_response([savings_call(result=result)], None)

    assert "100,0% laisvų pinigų" in text
    assert "viršija laisvus pinigus" not in text


def test_assumptions_section_handles_unmapped_field_and_origin_gracefully():
    """Defensive design: an unexpected `field`/`origin` string (not one of the 3/4 known values)
    must degrade to the raw string, not crash and not produce a blank/ugly fallback."""
    assumptions = [
        {
            "field": "some_future_field",
            "value": 12.3,
            "origin": "SOME_FUTURE_ORIGIN",
            "source": None,
            "source_date": None,
            "is_illustrative": None,
        }
    ]
    result = {
        "tier": "FREE",
        "core": {
            "monthly_contribution": 10.0,
            "free_cash_monthly": 20.0,
            "share_of_free_cash_pct": 5.0,
            "fits": True,
            "general_tip": "tip",
            "assumptions": assumptions,
            "disclaimer": "disc",
        },
        "locked_features": [],
        "income_origin": "USER",
    }

    text = build_response([savings_call(result=result)], None)

    assert "some_future_field" in text
    assert "SOME_FUTURE_ORIGIN" in text


# --- Owner instruction (2026-11-16, after B6 eval #11, item 3): anon/logged-in/IV-income CTA -----

def _savings_result(locked_features, income_origin="TAX_CALC", logged_in=False):
    result = {
        "core": {
            "monthly_contribution": 100.0,
            "free_cash_monthly": 400.0,
            "share_of_free_cash_pct": 25.0,
            "fits": True,
            "general_tip": "tip",
            "assumptions": [],
            "disclaimer": "disc",
        },
        "locked_features": locked_features,
        "income_origin": income_origin,
    }
    if logged_in:
        result["premium"] = {}
    return result


def test_anonymous_iv_income_gets_the_iv_svyruoja_sentence():
    tool_calls = [savings_call(result=_savings_result(["SAVINGS_IV_LINK"], income_origin="TAX_CALC"))]

    text = build_response(tool_calls, None)

    assert LOCKED_FEATURE_CTA["SAVINGS_IV_LINK"] in text
    assert "jei prisijungsite" in text.lower()


def test_anonymous_non_iv_income_gets_the_neutral_variant_without_svyruoja_sentence():
    tool_calls = [savings_call(result=_savings_result(["SAVINGS_IV_LINK"], income_origin="USER"))]

    text = build_response(tool_calls, None)

    assert LOCKED_FEATURE_CTA_NEUTRAL_IV in text
    assert "svyruoja" not in text.lower()
    assert "jei prisijungsite" in text.lower()


def test_logged_in_free_user_never_sees_jei_prisijungsi_gets_upgrade_wording_instead():
    tool_calls = [savings_call(
        result=_savings_result(["SAVINGS_IV_LINK"], income_origin="TAX_CALC", logged_in=True))]

    text = build_response(tool_calls, None)

    assert LOGGED_IN_FREE_CTA["SAVINGS_IV_LINK"] in text
    assert "jei prisijungsite" not in text.lower()
    assert "premium" in text.lower()


def test_logged_in_free_user_non_iv_income_gets_the_logged_in_neutral_variant():
    tool_calls = [savings_call(
        result=_savings_result(["SAVINGS_IV_LINK"], income_origin="USER", logged_in=True))]

    text = build_response(tool_calls, None)

    assert LOGGED_IN_FREE_CTA_NEUTRAL_IV in text
    assert "svyruoja" not in text.lower()


def test_logged_in_user_with_no_locked_features_gets_no_cta_at_all_beta_equivalent():
    # During beta every logged-in user gets all 3 features -> empty locked_features -> no CTA,
    # with no extra beta-flag plumbing needed in response_builder itself.
    tool_calls = [savings_call(result=_savings_result([], logged_in=True))]

    text = build_response(tool_calls, None)

    assert "prisijung" not in text.lower()
    assert "premium" not in text.lower()


def test_logged_in_free_multi_goal_feature_uses_the_logged_in_wording_too():
    tool_calls = [savings_call(
        result=_savings_result(["SAVINGS_MULTI_GOAL"], income_origin="USER", logged_in=True))]

    text = build_response(tool_calls, None)

    assert LOGGED_IN_FREE_CTA["SAVINGS_MULTI_GOAL"] in text


# --- Owner instruction (2026-11-16, after B6 eval #11, item 2): WHEN-mode reach date ------------

def test_when_mode_renders_the_goal_reach_date():
    result = {
        "core": {
            "monthly_contribution": 100.0,
            "free_cash_monthly": 400.0,
            "share_of_free_cash_pct": 25.0,
            "fits": True,
            "general_tip": "tip",
            "assumptions": [],
            "disclaimer": "disc",
            "reachable": True,
            "reach_date": "2027-03-13",
        },
        "locked_features": [],
    }
    tool_calls = [savings_call(result=result, input={"goal": {"mode": "WHEN"}})]

    text = build_response(tool_calls, None)

    assert "2027 m. kovo mėn." in text
    assert "mėn.." not in text  # item 4d: no stray double period from _format_date_lt's own "."


def test_when_mode_reach_date_with_a_non_fitting_contribution_renders_the_conditional_sentence():
    """Owner instruction (2026-11-16, after eval a2dbaae, item 4c): when the user's own stated
    contribution doesn't fit their free cash, the reach date must be framed conditionally
    ("Jei pavyktų atidėti..."), not as a plain achievable fact."""
    result = {
        "core": {
            "monthly_contribution": 500.0,
            "free_cash_monthly": 400.0,
            "share_of_free_cash_pct": 125.0,
            "fits": False,
            "general_tip": "tip",
            "assumptions": [],
            "disclaimer": "disc",
            "reachable": True,
            "reach_date": "2027-03-13",
        },
        "locked_features": [],
    }
    tool_calls = [savings_call(result=result, input={"goal": {"mode": "WHEN"}})]

    text = build_response(tool_calls, None)

    assert "Jei pavyktų atidėti 500,00 € per mėnesį, tikslą pasiektumėte 2027 m. kovo mėn." in text
    assert "Su nurodyta įmoka tikslą pasieksite" not in text
    assert "mėn.." not in text


def test_when_mode_unreachable_within_50_years_renders_the_unreachable_sentence():
    result = {
        "core": {
            "monthly_contribution": 1.0,
            "free_cash_monthly": 400.0,
            "share_of_free_cash_pct": 0.3,
            "fits": True,
            "general_tip": "tip",
            "assumptions": [],
            "disclaimer": "disc",
            "reachable": False,
            "reach_date": None,
        },
        "locked_features": [],
    }
    tool_calls = [savings_call(result=result, input={"goal": {"mode": "WHEN"}})]

    text = build_response(tool_calls, None)

    assert "nepasiekiamas per 50 metų" in text


def test_non_when_mode_never_renders_a_reach_date_sentence_even_if_core_has_one():
    """Defensive: Core.reachable/reachDate are always true/null for non-WHEN modes (Java-side
    guarantee) - but the template itself must gate strictly on the REQUEST's goal.mode, not on
    whether core happens to carry a reach_date, so a future Java change can't silently leak an
    irrelevant date into a YEARS/DATE/CHILD_AGE reply."""
    result = {
        "core": {
            "monthly_contribution": 100.0,
            "free_cash_monthly": 400.0,
            "share_of_free_cash_pct": 25.0,
            "fits": True,
            "general_tip": "tip",
            "assumptions": [],
            "disclaimer": "disc",
            "reachable": True,
            "reach_date": "2027-03-13",
        },
        "locked_features": [],
    }
    tool_calls = [savings_call(result=result, input={"goal": {"mode": "YEARS"}})]

    text = build_response(tool_calls, None)

    assert "2027" not in text
    assert "pasieksite" not in text


def test_when_mode_with_missing_input_shape_degrades_gracefully_no_crash():
    """Defensive: a tool_calls fixture with no `input` (or an input missing `goal`) must not crash
    - goal_mode resolves to None, same as a non-WHEN call."""
    result = {
        "core": {
            "monthly_contribution": 100.0,
            "free_cash_monthly": 400.0,
            "share_of_free_cash_pct": 25.0,
            "fits": True,
            "general_tip": "tip",
            "assumptions": [],
            "disclaimer": "disc",
            "reachable": True,
            "reach_date": "2027-03-13",
        },
        "locked_features": [],
    }
    tool_calls = [savings_call(result=result, input={})]

    text = build_response(tool_calls, None)

    assert "pasieksite" not in text


def test_cta_section_handles_unmapped_locked_feature_gracefully_no_crash_no_raw_dump():
    """An unrecognized `locked_features` entry is still a valid `pick_cta_feature` return value
    (no mapping to validate against there), but `LOCKED_FEATURE_CTA.get(...)` has no sentence for
    it - the CTA section must be silently omitted, not show a raw/ugly fallback string."""
    result = {
        "tier": "FREE",
        "core": {
            "monthly_contribution": 10.0,
            "free_cash_monthly": 20.0,
            "share_of_free_cash_pct": 5.0,
            "fits": True,
            "general_tip": "tip",
            "assumptions": [],
            "disclaimer": "disc",
        },
        "locked_features": ["SOME_FUTURE_LOCKED_FEATURE"],
        "income_origin": "USER",
    }

    assert pick_cta_feature(["SOME_FUTURE_LOCKED_FEATURE"]) == "SOME_FUTURE_LOCKED_FEATURE"

    text = build_response([savings_call(result=result)], None)

    assert "SOME_FUTURE_LOCKED_FEATURE" not in text
    assert "prisijung" not in text.lower()


def test_origin_labels_exactly_match_the_established_vocabulary_in_taupymo_tikslai_html():
    """AC-B4-1: `ORIGIN_LABELS` must be a verbatim port of the JS `ORIGIN_LABELS` object in
    taupymo-tikslai.html - reads the real template source so this fails if either side drifts."""
    import pathlib
    import re

    from app.response_builder import ORIGIN_LABELS

    html_path = (
        pathlib.Path(__file__).resolve().parents[2]
        / "src/main/resources/templates/taupymo-tikslai.html"
    )
    html = html_path.read_text(encoding="utf-8")
    match = re.search(r'var ORIGIN_LABELS = \{([^}]*)\};', html)
    assert match is not None, "ORIGIN_LABELS not found in taupymo-tikslai.html - template moved?"
    pairs = re.findall(r'(\w+):\s*"([^"]*)"', match.group(1))
    expected = dict(pairs)

    assert expected, "regex extracted nothing - check the template's ORIGIN_LABELS literal syntax"
    assert ORIGIN_LABELS == expected


def test_locked_feature_cta_sentences_are_no_longer_required_to_match_html_source():
    """Owner instruction (2026-11-16, after B6 eval #11, item 6a): "kreipinys 'jūs' visur" in this
    chat template - `LOCKED_FEATURE_CTA` flipped to formal "jūs" register, while
    taupymo-tikslai.html's own JS object (out of this item's scope) stays informal "tu". The old
    byte-for-byte sync test (`test_locked_feature_cta_sentences_match_html_source_byte_for_byte`,
    removed) enforced the OPPOSITE invariant (the two must always match) back when both were
    deliberately the same informal voice - that invariant no longer holds, by owner decision, so
    this replaces it with the current, intentional fact instead of silently deleting coverage."""
    import pathlib
    import re

    from app.response_builder import LOCKED_FEATURE_CTA

    html_path = (
        pathlib.Path(__file__).resolve().parents[2]
        / "src/main/resources/templates/taupymo-tikslai.html"
    )
    html = html_path.read_text(encoding="utf-8")
    match = re.search(r"var LOCKED_FEATURE_CTA = \{(.*?)\};", html, re.DOTALL)
    assert match is not None, "LOCKED_FEATURE_CTA not found in taupymo-tikslai.html - template moved?"
    block = match.group(1)
    entries = re.findall(r'(\w+):\s*((?:"[^"]*"\s*\+?\s*)+)', block)
    html_sentences = {key: "".join(re.findall(r'"([^"]*)"', chunks)) for key, chunks in entries}

    assert set(html_sentences) == set(LOCKED_FEATURE_CTA), "feature coverage should still match"
    # The HTML page is still informal ("tu"/"tavo") - the chat template is now formal ("jūs").
    for key in html_sentences:
        assert "tavo" in html_sentences[key].lower() or "prisijungsi" in html_sentences[key].lower()
        assert LOCKED_FEATURE_CTA[key] != html_sentences[key]


def test_locked_feature_cta_and_logged_in_free_cta_use_formal_jus_register_throughout():
    """Owner instruction (2026-11-16, after B6 eval #11, item 6a): every CTA sentence must use the
    formal "jūs" register, not informal "tu"/"tavo"."""
    from app.response_builder import (
        LOCKED_FEATURE_CTA,
        LOCKED_FEATURE_CTA_NEUTRAL_IV,
        LOGGED_IN_FREE_CTA,
        LOGGED_IN_FREE_CTA_NEUTRAL_IV,
    )

    all_sentences = (
        list(LOCKED_FEATURE_CTA.values()) + [LOCKED_FEATURE_CTA_NEUTRAL_IV]
        + list(LOGGED_IN_FREE_CTA.values()) + [LOGGED_IN_FREE_CTA_NEUTRAL_IV]
    )
    for sentence in all_sentences:
        lowered = sentence.lower()
        assert "tavo" not in lowered, sentence
        assert " tu " not in f" {lowered} ", sentence
        assert "prisijungsi," not in lowered and "prisijungsi " not in lowered, sentence
        assert "atnaujinsi," not in lowered and "atnaujinsi " not in lowered, sentence


def test_general_tip_is_rendered_verbatim_no_informal_rewrite():
    """Owner instruction (2026-11-16, after B6 eval #11, item 6a): Java's generalTip() is already
    formal "jūs" - the B4-era GENERAL_TIP_INFORMAL_LT rewrite-to-informal map (which existed to
    match the CTA's then-informal voice) is removed; the formal Java text now passes through
    unchanged, consistent with the rest of this template's formal register."""
    tool_calls = [savings_call(result=SAVINGS_RESULT)]

    text = build_response(tool_calls, None)

    assert SAVINGS_RESULT["core"]["general_tip"] in text
