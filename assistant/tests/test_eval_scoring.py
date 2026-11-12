"""Behavioral unit tests for every function in `evals/agent/scoring.py` (B6, AC-B6-2/3/4/5/6).

Pure-function tests: `tool_calls` are hand-built plain dicts matching the real
`AgentLoopResult.tool_calls` shape (see `agent_loop.py`'s own docstring: each entry is
`{"id", "name", "input", "result": dict | None, "error": str | None, "is_error": bool}`) - no
real `AgentLoopResult`/Anthropic objects, no live API calls.
"""
from decimal import Decimal

from app.response_builder import SAVINGS_TOOL_NAME, TAX_TOOL_NAME
from evals.agent.scoring import (
    BANNED_INVESTMENT_PATTERNS,
    extract_numbers,
    rendered_numbers,
    score_anon_no_premium,
    score_missing_income_handled,
    score_no_banned_investment_terms,
    score_no_invented_numbers,
    score_tool_sequence,
)


def tax_call(call_id="t1", is_error=False, result=None, error=None):
    return {
        "id": call_id,
        "name": TAX_TOOL_NAME,
        "input": {},
        "result": result,
        "error": error,
        "is_error": is_error,
    }


def savings_call(call_id="s1", is_error=False, result=None, error=None):
    return {
        "id": call_id,
        "name": SAVINGS_TOOL_NAME,
        "input": {},
        "result": result,
        "error": error,
        "is_error": is_error,
    }


TAX_RESULT = {
    "tax_calc_id": "abc123",
    "method": "ACTUAL",
    "total_tax_per_year": 1234.56,
    "monthly_tax_setaside": 102.88,
    "net_monthly_income": 987.65,
}

SAVINGS_RESULT_NO_PREMIUM = {
    "tier": "FREE",
    "core": {
        "monthly_contribution": 200.0,
        "free_cash_monthly": 400.0,
        "share_of_free_cash_pct": 50.0,
        "general_tip": "tip",
        "assumptions": [],
        "disclaimer": "disc",
    },
    "locked_features": [],
    "income_origin": "USER",
}

SAVINGS_RESULT_WITH_PREMIUM = {
    "tier": "PREMIUM",
    "core": {
        "monthly_contribution": 300.0,
        "free_cash_monthly": 500.0,
        "share_of_free_cash_pct": 60.0,
        "general_tip": "tip",
        "assumptions": [],
        "disclaimer": "disc",
    },
    "locked_features": [],
    "income_origin": "USER",
    "premium": {"something": 1},
}


# --- score_tool_sequence (AC-B6-2) ---------------------------------------------------------------

def test_score_tool_sequence_passes_on_exact_match():
    calls = [tax_call(), savings_call()]
    assert score_tool_sequence(calls, [TAX_TOOL_NAME, SAVINGS_TOOL_NAME]) is True


def test_score_tool_sequence_fails_on_wrong_order():
    calls = [savings_call(), tax_call()]
    assert score_tool_sequence(calls, [TAX_TOOL_NAME, SAVINGS_TOOL_NAME]) is False


def test_score_tool_sequence_fails_on_missing_call():
    calls = [tax_call()]
    assert score_tool_sequence(calls, [TAX_TOOL_NAME, SAVINGS_TOOL_NAME]) is False


def test_score_tool_sequence_fails_on_extra_call():
    calls = [tax_call(), savings_call()]
    assert score_tool_sequence(calls, [TAX_TOOL_NAME]) is False


def test_score_tool_sequence_passes_on_empty_expected_and_empty_actual():
    assert score_tool_sequence([], []) is True


def test_score_tool_sequence_includes_error_calls_in_the_actual_sequence():
    # AC-B6-2: ALL calls, successful or not, count towards the actual sequence.
    calls = [tax_call(is_error=True, error="boom")]
    assert score_tool_sequence(calls, [TAX_TOOL_NAME]) is True
    assert score_tool_sequence(calls, []) is False


# --- extract_numbers ------------------------------------------------------------------------------

def test_extract_numbers_parses_lithuanian_formatted_number_with_euro_sign():
    assert extract_numbers("Tikslas yra 1 234,56 € per mėnesį.") == {Decimal("1234.56")}


def test_extract_numbers_parses_plain_decimal_and_integer():
    assert extract_numbers("Liko 123.45 ir dar 42.") == {Decimal("123.45"), Decimal("42")}


def test_extract_numbers_empty_text_returns_empty_set():
    assert extract_numbers("") == set()


def test_extract_numbers_text_with_no_numbers_returns_empty_set():
    assert extract_numbers("Sveika, kuo galiu padėti?") == set()


# --- rendered_numbers ------------------------------------------------------------------------------

def test_rendered_numbers_collects_numeric_leaves_from_last_successful_tax_result():
    calls = [tax_call(result=TAX_RESULT)]
    numbers = rendered_numbers(calls)
    assert Decimal("1234.56") in numbers
    assert Decimal("102.88") in numbers
    assert Decimal("987.65") in numbers


def test_rendered_numbers_excludes_booleans():
    # bool is an int subclass in Python - a True/False leaf must NOT be collected as a number.
    result = {**TAX_RESULT, "pension_accumulation": True, "other_flag": False}
    calls = [tax_call(result=result)]
    numbers = rendered_numbers(calls)
    assert all(not isinstance(n, bool) for n in numbers)


def test_rendered_numbers_ignores_failed_calls():
    calls = [tax_call(is_error=True, result=None, error="boom")]
    assert rendered_numbers(calls) == set()


def test_rendered_numbers_uses_last_successful_result_per_tool_not_the_first():
    first = {**TAX_RESULT, "total_tax_per_year": 1.0}
    second = {**TAX_RESULT, "total_tax_per_year": 2.0}
    calls = [tax_call(call_id="t1", result=first), tax_call(call_id="t2", result=second)]
    numbers = rendered_numbers(calls)
    assert Decimal("2.0") in numbers or Decimal("2") in numbers
    assert Decimal("1.0") not in numbers and Decimal("1") not in numbers


# --- score_no_invented_numbers (AC-B6-3) -----------------------------------------------------------

def test_score_no_invented_numbers_passes_when_free_text_repeats_a_rendered_number():
    calls = [tax_call(result=TAX_RESULT)]
    final_text = "Metinė mokesčių suma yra 1234.56 eurų."
    assert score_no_invented_numbers(final_text, calls) is True


def test_score_no_invented_numbers_fails_when_free_text_states_an_unrendered_number():
    calls = [tax_call(result=TAX_RESULT)]
    final_text = "Apytiksliai tai bus apie 999999 eurų."
    assert score_no_invented_numbers(final_text, calls) is False


def test_score_no_invented_numbers_passes_trivially_on_empty_text():
    calls = [tax_call(result=TAX_RESULT)]
    assert score_no_invented_numbers("", calls) is True
    assert score_no_invented_numbers(None, calls) is True


def test_score_no_invented_numbers_passes_when_free_text_has_no_numbers_at_all():
    calls = [tax_call(result=TAX_RESULT)]
    assert score_no_invented_numbers("Štai tavo suvestinė, kaip paprašei.", calls) is True


# --- score_anon_no_premium (AC-B6-5) ---------------------------------------------------------------

def test_score_anon_no_premium_fails_if_anon_successful_result_carries_premium_key():
    calls = [savings_call(result=SAVINGS_RESULT_WITH_PREMIUM)]
    assert score_anon_no_premium(calls, "anon") is False


def test_score_anon_no_premium_passes_for_anon_without_premium_key():
    calls = [savings_call(result=SAVINGS_RESULT_NO_PREMIUM)]
    assert score_anon_no_premium(calls, "anon") is True


def test_score_anon_no_premium_passes_vacuously_for_logged_in_regardless_of_premium():
    calls = [savings_call(result=SAVINGS_RESULT_WITH_PREMIUM)]
    assert score_anon_no_premium(calls, "logged_in") is True


def test_score_anon_no_premium_passes_vacuously_when_no_savings_result_present():
    assert score_anon_no_premium([], "anon") is True


# --- score_missing_income_handled (AC-B6-6) ---------------------------------------------------------

def test_score_missing_income_handled_passes_on_a_clean_clarifying_question():
    final_text = "Kiek yra tavo grynosios mėnesio pajamos?"
    assert score_missing_income_handled([], final_text) is True


def test_score_missing_income_handled_fails_if_a_successful_savings_result_exists():
    calls = [savings_call(result=SAVINGS_RESULT_NO_PREMIUM)]
    final_text = "Ar galėtum patikslinti pajamas?"
    assert score_missing_income_handled(calls, final_text) is False


def test_score_missing_income_handled_tolerates_a_failed_savings_attempt_then_a_question():
    calls = [savings_call(is_error=True, result=None, error="Nėra grynųjų pajamų")]
    final_text = "Kokios yra tavo mėnesio grynosios pajamos?"
    assert score_missing_income_handled(calls, final_text) is True


def test_score_missing_income_handled_fails_if_question_text_contains_a_number():
    final_text = "Ar tavo pajamos yra apie 1500 eurų?"
    assert score_missing_income_handled([], final_text) is False


def test_score_missing_income_handled_fails_if_text_has_no_question_mark():
    final_text = "Reikėtų žinoti tavo pajamas."
    assert score_missing_income_handled([], final_text) is False


def test_score_missing_income_handled_fails_on_empty_text():
    assert score_missing_income_handled([], "") is False
    assert score_missing_income_handled([], None) is False


# --- score_no_banned_investment_terms (AC-B6-4, deterministic half) ---------------------------------

def test_score_no_banned_investment_terms_fails_on_banned_phrase():
    assert BANNED_INVESTMENT_PATTERNS, "expected at least one banned pattern"
    sample_phrase = BANNED_INVESTMENT_PATTERNS[0]
    text = f"Štai patarimas: {sample_phrase} ir pamatysi rezultatus."
    assert score_no_banned_investment_terms(text) is False


def test_score_no_banned_investment_terms_is_case_insensitive():
    sample_phrase = BANNED_INVESTMENT_PATTERNS[0]
    text = f"PATARIMAS: {sample_phrase.upper()}!"
    assert score_no_banned_investment_terms(text) is False


def test_score_no_banned_investment_terms_passes_on_clean_text():
    text = "Čia bendras paaiškinimas apie taupymą, be konkrečių produktų rekomendacijų."
    assert score_no_banned_investment_terms(text) is True


def test_score_no_banned_investment_terms_passes_on_none_or_empty():
    assert score_no_banned_investment_terms(None) is True
    assert score_no_banned_investment_terms("") is True
