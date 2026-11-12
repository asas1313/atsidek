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
    assert "nespėlio" in lowered or "neprielei" in lowered


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
    assert re.search(r"(nespėlio|neprielei)\w*.{0,60}pajam", lowered) or re.search(
        r"pajam\w*.{0,80}statistik", lowered
    )


def test_prompt_explicitly_bans_approximate_or_illustrative_numbers_too_not_just_exact_ones():
    """A model that says 'roughly 300 euros' while reserving exact figures for the template
    would technically satisfy a looser 'never state A number' reading - the prompt must close
    that loophole explicitly."""
    lowered = SYSTEM_PROMPT.lower()
    assert "apytikslio" in lowered or "iliustracinio" in lowered
