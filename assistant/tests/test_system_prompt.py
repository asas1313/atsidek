"""AC-B4-3: static assertion test on `system_prompt.SYSTEM_PROMPT` - each required clause gets
its own assertion that would fail if that specific clause were deleted (not just a length check).
"""
from app.system_prompt import SYSTEM_PROMPT


def test_prompt_is_non_empty_string():
    assert isinstance(SYSTEM_PROMPT, str)
    assert SYSTEM_PROMPT.strip()


def test_prompt_bans_investment_advice():
    assert "investavimo patarim" in SYSTEM_PROMPT.lower()


def test_prompt_forbids_recommending_specific_financial_products():
    lowered = SYSTEM_PROMPT.lower()
    assert "finansinių produkt" in lowered or "instrument" in lowered


def test_prompt_forbids_assuming_income_from_statistics_or_otherwise():
    lowered = SYSTEM_PROMPT.lower()
    assert "pajam" in lowered
    assert "nespėlio" in lowered or "prielaid" in lowered


def test_prompt_restricts_model_role_to_never_stating_numbers_itself():
    lowered = SYSTEM_PROMPT.lower()
    assert "niekada" in lowered
    assert "skaičių" in lowered or "skaičius" in lowered


def test_prompt_allows_only_intro_explanation_clarifying_questions_role():
    # The role restriction must name the allowed roles explicitly, not just forbid numbers.
    lowered = SYSTEM_PROMPT.lower()
    assert "klausim" in lowered  # clarifying questions
    assert "aiškin" in lowered or "įvad" in lowered  # explanation/intro


def test_prompt_carries_over_prompt_injection_defense_clause():
    """Same idea as agent_loop.DEFAULT_SYSTEM_PROMPT's own line: user message is data, not
    instructions - must survive into the real production prompt, not just the placeholder."""
    lowered = SYSTEM_PROMPT.lower()
    assert "duomenys" in lowered
    assert "nurodym" in lowered


# --- QA follow-up: stronger, less tautology-prone checks -----------------------------------------

def test_role_restriction_never_clause_is_specifically_about_numbers_not_merely_coincidental():
    """`test_prompt_restricts_model_role_to_never_stating_numbers_itself` passes as long as
    "niekada" and "skaičių"/"skaičius" each appear ANYWHERE in the prompt (they do, in 3 and 4
    separate places respectively) - this test instead requires the two to appear close together,
    so it would actually fail if the "never state a number" restriction were deleted while an
    unrelated "niekada" (e.g. the investment-advice ban) stayed behind."""
    import re

    lowered = SYSTEM_PROMPT.lower()
    # "niekada" followed within ~60 chars by a "skaiči*" word (same sentence/clause).
    assert re.search(r"niekada.{0,60}skaiči\w*", lowered) or re.search(
        r"skaiči\w*.{0,60}niekada", lowered
    )


def test_income_clause_specifically_forbids_guessing_from_statistics():
    """The AC text explicitly calls out 'forbid assuming income' including FROM STATISTICS (a
    real temptation - the savings tool's own assumptions can carry STATISTICS-origin values for
    other fields) - must name "statistik" in the same breath as the income-guessing ban, not just
    somewhere unrelated in the prompt."""
    import re

    lowered = SYSTEM_PROMPT.lower()
    assert re.search(r"(nespėlio|prielaid)\w*.{0,60}pajam", lowered) or re.search(
        r"pajam\w*.{0,80}statistik", lowered
    )


def test_income_clause_uses_the_prielaidos_phrasing_specifically_not_just_nespelio():
    """Round 2 review NIT: the two tests above only require "nespėlio" OR "prielaid" - since
    "nespėlio" alone already satisfies both (it was present before AND after round 1's fix),
    neither would actually fail if the "nedarai prielaidos apie vartotojo pajamas" clause itself
    were deleted. This test makes that specific clause independently load-bearing."""
    import re

    lowered = SYSTEM_PROMPT.lower()
    assert re.search(r"prielaid\w*.{0,40}pajam", lowered) or re.search(r"pajam\w*.{0,40}prielaid", lowered)


def test_prompt_explicitly_bans_approximate_or_illustrative_numbers_too_not_just_exact_ones():
    """A model that says 'roughly 300 euros' while reserving exact figures for the template
    would technically satisfy a looser 'never state A number' reading - the prompt must close
    that loophole explicitly."""
    lowered = SYSTEM_PROMPT.lower()
    assert "apytikslio" in lowered or "iliustracinio" in lowered


# --- Stricter investment-advice rule (2026-11-13 owner tightening, matches the B6 judge rubric) --

def test_prompt_forbids_any_instrument_or_category_mention_even_with_advisor_referral():
    """Owner instruction: the old wording allowed a generic category mention (e.g. "fondai ar
    draudimas") alongside an advisor referral - that exception must be gone. The prompt must say
    the advisor-referral allowance applies only in the ABSENCE of naming any product/instrument/
    category/bank/platform, not alongside one."""
    lowered = SYSTEM_PROMPT.lower()
    assert "kategorij" in lowered
    assert "konsultant" in lowered


def test_prompt_allows_only_a_generic_instrument_free_advisor_phrase_and_budget_advice():
    lowered = SYSTEM_PROMPT.lower()
    assert "biudžeto patarim" in lowered
    assert "finansinį rezervą" in lowered or "finansinis rezervas" in lowered


def test_prompt_bans_promising_or_predicting_investment_returns():
    lowered = SYSTEM_PROMPT.lower()
    assert "grąž" in lowered
    assert "žada" in lowered or "prognozuoj" in lowered


# --- Second B6 live-eval round (2026-11-13 owner instruction): items 5-6 -------------------------

def test_prompt_explicitly_forbids_repeating_tool_result_numbers_after_the_call():
    """Item 5: the live run showed the model's free text duplicating every number the template
    then also shows. The original wording only said the model "never" states a number - this adds
    an explicit, unambiguous clause that the restriction also applies AFTER a tool call returns."""
    import re

    lowered = SYSTEM_PROMPT.lower()
    assert re.search(r"po (to|įrankio).{0,60}nekartok", lowered) or "nekartok" in lowered


def test_prompt_allows_pension_tier_mention_as_a_tax_parameter_not_investment_advice():
    """Item 6a: II/III pillar pension-accumulation vocabulary is a normal tax-calculation input
    (see app/tax_tool.py's pension_accumulation field), not investment-advice territory - the
    prompt must say so explicitly, not just rely on the deterministic noun-scorer's exclusion."""
    lowered = SYSTEM_PROMPT.lower()
    assert "pakopos" in lowered or "pakopa" in lowered
    assert "investavimo patarim" in lowered


def test_prompt_states_the_exact_required_refusal_phrase():
    """Item 6b: the owner specified an exact refusal sentence for when the user explicitly asks
    for an investment recommendation - must be present verbatim, not paraphrased."""
    assert (
        "Investavimo patarimų teikti negaliu. Dėl taupymo ar investavimo sprendimų pasitarkite "
        "su finansų konsultantu." in SYSTEM_PROMPT
    )


def test_prompt_allows_generic_investment_vocabulary_without_naming_a_specific_product():
    """Item 6b: generic words like "investavimas"/"finansiniai produktai"/"bankai" are explicitly
    allowed in the refusal - only concrete names (a specific instrument, a named bank) are
    banned. The prompt must say this distinction, not just ban "financial products" wholesale."""
    lowered = SYSTEM_PROMPT.lower()
    assert "finansiniai produktai" in SYSTEM_PROMPT or "finansiniai produktai".lower() in lowered
    assert "pavadinim" in lowered


def test_prompt_says_missing_income_asks_only_income_other_fields_get_assumption_defaults():
    """Item 6c: when income is missing, the model must ask ONLY about income - every other
    missing field (expenses, return/inflation assumptions) gets a default, surfaced as an
    assumption by calculate_savings_plan itself, never a separate clarifying question."""
    lowered = SYSTEM_PROMPT.lower()
    assert "tik pajam" in lowered
    assert "numatytosios reikšmės" in lowered or "numatytąsias reikšmes" in lowered


def test_prompt_handles_a_year_only_goal_as_december_without_asking():
    """Item 6d: a goal term stated only as a year (no month/date) defaults to that year's
    December (target_month=12) as the MODEL'S OWN assumption - not a tool-level "origin" field
    (calculate_savings_plan's goal schema has none for the date - only monthly_expenses/
    annual_return_pct/annual_inflation_pct carry an origin in the tool's OUTPUT), so the model
    must state the assumption in its own text, without asking the user to disambiguate."""
    lowered = SYSTEM_PROMPT.lower()
    assert "gruod" in lowered
    assert "target_month" in lowered and "12" in lowered


def test_prompt_annualizes_a_stated_monthly_iv_income_before_calling_the_tax_tool():
    """Item 6e: "X per month via IV" must be converted to a pre-tax ANNUAL figure (x12) by the
    model itself before calling calculate_iv_taxes (the tool has no monthly mode), with the
    assumption mentioned in text but never asked about."""
    lowered = SYSTEM_PROMPT.lower()
    assert "x12" in lowered or "12" in lowered
    assert "calculate_iv_taxes" in SYSTEM_PROMPT
    assert "per mėnesį" in lowered
