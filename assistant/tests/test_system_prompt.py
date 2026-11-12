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
