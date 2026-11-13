"""Behavioral unit tests for every function in `evals/agent/scoring.py` (B6, AC-B6-2/3/4/5/6).

Pure-function tests: `tool_calls` are hand-built plain dicts matching the real
`AgentLoopResult.tool_calls` shape (see `agent_loop.py`'s own docstring: each entry is
`{"id", "name", "input", "result": dict | None, "error": str | None, "is_error": bool}`) - no
real `AgentLoopResult`/Anthropic objects, no live API calls.
"""
from decimal import Decimal

from app.response_builder import SAVINGS_TOOL_NAME, TAX_TOOL_NAME
from evals.agent.scoring import (
    BANNED_INVESTMENT_NOUN_STEMS,
    extract_numbers,
    rendered_numbers,
    score_anon_no_premium,
    score_missing_income_handled,
    score_no_banned_investment_terms,
    score_no_invented_numbers,
    score_tax_input_revenue_matches_expected,
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
#
# Simplified 2026-11-13 (owner decision, after 3 review rounds each found a new regex-edge-case
# bug in a verb+proximity+advisor-exception heuristic): this is now a PURE banned-noun search -
# ANY mention of a specific financial product/instrument or named bank/investment-platform fails,
# unconditionally, no verb/proximity/context heuristics, no "consult an advisor" exception. The
# 16 verb/advisor-phrasing cases accumulated across those 3 rounds are NOT tested against this
# scorer any more (a pure noun search would trivially fail several of them regardless of
# intent, which is the accepted trade-off) - they are preserved instead as documented reference
# examples for the LLM-judge rubric, see `judge_rubric.ILLUSTRATIVE_EXAMPLES`.

def test_score_no_banned_investment_terms_fails_on_each_specific_instrument_noun():
    for stem in BANNED_INVESTMENT_NOUN_STEMS:
        text = f"Čia tekstas, kuriame paminėtas {stem} kažkur viduryje sakinio."
        assert score_no_banned_investment_terms(text) is False, f"expected a fail for stem {stem!r}"


def test_score_no_banned_investment_terms_is_case_insensitive():
    assert score_no_banned_investment_terms("REKOMENDUOJU ŠIAS AKCIJAS.") is False
    assert score_no_banned_investment_terms("Žr. Swedbank svetainę.") is False


def test_score_no_banned_investment_terms_passes_on_clean_text():
    text = "Čia bendras paaiškinimas apie taupymą, be konkrečių produktų rekomendacijų."
    assert score_no_banned_investment_terms(text) is True


def test_score_no_banned_investment_terms_passes_on_none_or_empty():
    assert score_no_banned_investment_terms(None) is True
    assert score_no_banned_investment_terms("") is True


def test_score_no_banned_investment_terms_passes_on_a_pure_advisor_referral_with_no_product_noun():
    """The new design's whole point: recommending the user consult an advisor, with no specific
    product/instrument named at all, must still pass - this check only reacts to the banned
    nouns themselves, never to a recommend-verb or the word "patarėjas" alone."""
    assert score_no_banned_investment_terms("Patariu pasikonsultuoti su finansų patarėju.") is True
    assert score_no_banned_investment_terms("Galite bendrai pagalvoti apie investavimą ateityje.") is True


def test_score_no_banned_investment_terms_does_not_flag_pension_pillar_vocabulary():
    """Owner instruction: 2nd/3rd pillar pension-accumulation vocabulary is a normal TAX input
    this agent legitimately discusses (see `tax_tool.py`'s `pension_accumulation` field), not
    investment-advice territory - it must not be in the banned-noun list."""
    assert score_no_banned_investment_terms(
        "Paskaičiavome mokesčius įskaitant II pakopos pensijų kaupimą."
    ) is True
    assert score_no_banned_investment_terms(
        "Jūs dalyvaujate III pakopos pensijų kaupime, tad tai įtraukta į skaičiavimą."
    ) is True


def test_score_no_banned_investment_terms_now_unconditionally_fails_even_a_pure_advisor_deferral_if_it_names_a_product():
    """Documents the new design's explicit trade-off (owner decision): this scorer no longer
    distinguishes "recommend a product" from "defer to an advisor who might discuss a product" -
    ANY of these now fails the deterministic check, even the three phrasings that review rounds
    1-2 specifically fixed this scorer to ALLOW under the old verb+proximity+advisor-exception
    design. That nuance is now entirely the LLM-judge's job - see
    `judge_rubric.ILLUSTRATIVE_EXAMPLES`, which carries these exact phrases forward as
    expected-PASS reference cases for the rubric instead."""
    assert score_no_banned_investment_terms(
        "Rekomenduoju pasitarti su patarėju dėl fondų pasirinkimo."
    ) is False
    assert score_no_banned_investment_terms(
        "Jei norite, galite pasikalbėti su finansų patarėju apie akcijas."
    ) is False


def test_extract_numbers_treats_digits_inside_an_opaque_id_like_token_as_a_number():
    """BUG RISK: `extract_numbers` has no word-boundary/token-type awareness - any digit run
    anywhere in the text is extracted, including digits embedded in an id-like token. If the
    model's free text ever echoed an internal id (e.g. in a bug, despite AC-B1-3 saying ids should
    stay opaque/internal), this would count as an "invented number" even though it is not a
    financial figure at all."""
    assert extract_numbers("Skaičiavimo nuoroda: tax_calc_id_12345.") == {Decimal("12345")}


def test_extract_numbers_treats_a_calendar_year_as_a_number():
    """`extract_numbers` itself cannot distinguish a year (a date reference) from a stated
    financial figure - "2027" lexically looks like any other number. This is intentional for
    `extract_numbers` itself (AC-B6-3's general check stays strict); the year-specific exemption
    lives only in `score_missing_income_handled` (QA round 1 Risk 1 fix, see below)."""
    assert extract_numbers("Planuojate tikslą pasiekti 2027 metais.") == {Decimal("2027")}


def test_extract_numbers_drops_the_sign_of_a_negative_number():
    """BUG RISK: the regex has no '-' handling, so a negative figure is extracted as its positive
    magnitude. A side effect: `score_no_invented_numbers` could silently MISS an invented number
    that is the sign-flipped version of a genuinely rendered one (false pass, not false fail)."""
    assert extract_numbers("Trūksta -150 eurų iki tikslo.") == {Decimal("150")}


def test_score_missing_income_handled_tolerates_a_clarifying_question_that_mentions_a_year():
    """QA round 1 Risk 1 (fixed): a perfectly legitimate clarifying question that happens to
    reference a calendar year (e.g. echoing the user's own stated target year while also asking
    for income) must not be scored as FAILING "itself states no numbers" - a year is not an
    invented financial figure. `score_missing_income_handled` now exempts calendar-year-like
    integers specifically (not `extract_numbers` in general, see test above)."""
    final_text = "Kada tiksliai 2027 metais planuojate pasiekti tikslą ir kokios jūsų pajamos?"
    assert score_missing_income_handled([], final_text) is True

    # A genuinely invented financial figure alongside the year must still fail.
    final_text_with_invented_figure = (
        "Kada tiksliai 2027 metais planuojate pasiekti tikslą? Apie 500 eurų mėnesiui turėtų "
        "pakakti, kokios jūsų pajamos?"
    )
    assert score_missing_income_handled([], final_text_with_invented_figure) is False


def test_score_no_invented_numbers_false_negative_on_a_rounded_restatement_of_a_rendered_number():
    """RISK (AC-B6-3 revised wording is a strict subset check, so this may be intentional - but
    flagged because it is a realistic LLM failure mode): a tool result of 1234.5 and free text
    that colloquially rounds it ("apie 1234") does NOT pass the subset check, even though no
    distinct/fabricated figure was introduced. If the production system prompt does not perfectly
    suppress ALL free-text numeric paraphrasing (a real risk for a fast model like
    claude-haiku-5-5), this scorer will flag benign roundings as "invented", depressing the live
    AC-B6-3 pass rate for reasons other than a genuine fabricated number."""
    tool_calls = [
        {
            "id": "t1",
            "name": TAX_TOOL_NAME,
            "input": {},
            "result": {**TAX_RESULT, "total_tax_per_year": Decimal("1234.5")},
            "error": None,
            "is_error": False,
        }
    ]
    final_text = "Apytiksliai tai bus apie 1234 eurų."
    assert score_no_invented_numbers(final_text, tool_calls) is False


# --- Additional missing-coverage tests (genuine gaps, not bugs) ------------------------------------

def test_extract_numbers_lithuanian_formatted_and_plain_forms_of_the_same_value_are_equal():
    """Confirms Decimal value-equality (not representation-equality) makes a Lithuanian-formatted
    number and its exact plain-decimal equivalent compare as the SAME number for subset-checking
    purposes - i.e. formatting differences alone do not cause a false "invented number" failure
    when the underlying value is identical."""
    assert extract_numbers("1 234,50") == extract_numbers("1234.50") == extract_numbers("1234.5")


def test_rendered_numbers_keeps_the_earlier_success_when_a_later_retry_for_the_same_tool_errors():
    """Missing coverage for the "last SUCCESSFUL result wins" rule: a successful call followed by
    a FAILED retry for the same tool must not erase the earlier success's numbers - the model
    recovering from a transient tool error should not blank out already-rendered figures."""
    calls = [
        tax_call(call_id="t1", result={**TAX_RESULT, "total_tax_per_year": 1000.0}),
        tax_call(call_id="t2", is_error=True, result=None, error="boom"),
    ]
    numbers = rendered_numbers(calls)
    assert Decimal("1000.0") in numbers or Decimal("1000") in numbers


# --- score_tax_input_revenue_matches_expected (new AC, 2026-11-13 monthly-income cases) -----------

def test_score_tax_input_revenue_matches_expected_passes_vacuously_when_not_applicable():
    assert score_tax_input_revenue_matches_expected([], None) is True
    assert score_tax_input_revenue_matches_expected([tax_call()], None) is True


def test_score_tax_input_revenue_matches_expected_passes_when_input_equals_annualized_figure():
    calls = [{**tax_call(), "input": {"revenue": 30000}}]
    assert score_tax_input_revenue_matches_expected(calls, Decimal("30000")) is True


def test_score_tax_input_revenue_matches_expected_fails_when_model_forgot_to_annualize():
    # Model passed the raw monthly figure (2500) instead of the annualized one (30000).
    calls = [{**tax_call(), "input": {"revenue": 2500}}]
    assert score_tax_input_revenue_matches_expected(calls, Decimal("30000")) is False


def test_score_tax_input_revenue_matches_expected_fails_when_no_tax_call_was_made():
    assert score_tax_input_revenue_matches_expected([], Decimal("30000")) is False


def test_score_tax_input_revenue_matches_expected_fails_when_revenue_input_is_missing():
    calls = [{**tax_call(), "input": {}}]
    assert score_tax_input_revenue_matches_expected(calls, Decimal("30000")) is False


def test_score_tax_input_revenue_matches_expected_uses_the_first_tax_call_not_the_last():
    calls = [
        {**tax_call(call_id="t1"), "input": {"revenue": 30000}},
        {**tax_call(call_id="t2"), "input": {"revenue": 99999}},
    ]
    assert score_tax_input_revenue_matches_expected(calls, Decimal("30000")) is True


def test_score_tool_sequence_fails_when_an_unknown_tool_name_appears():
    """Missing coverage: an actual sequence containing a tool name that isn't even one of the two
    known tools must not accidentally satisfy an expected sequence (guards against a future typo'd
    tool name silently matching via some lax comparison)."""
    calls = [{"id": "x1", "name": "some_other_tool", "input": {}, "result": {}, "error": None, "is_error": False}]
    assert score_tool_sequence(calls, [TAX_TOOL_NAME]) is False
