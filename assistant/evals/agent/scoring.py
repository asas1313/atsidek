"""Pure, deterministic scoring functions for the live agent eval (B6, AC-B6-2/3/4/5/6).

Every function here operates on plain data - an `AgentLoopResult`-shaped `tool_calls` list
(`list[dict]`, each `{"id", "name", "input", "result": dict | None, "error": str | None,
"is_error": bool}`, exactly `agent_loop.AgentLoopResult.tool_calls`'s own shape) and/or a
`final_text: Optional[str]` - so they are testable with lightweight fake dicts, never requiring a
real Anthropic client or a real `AgentLoopResult` instance. `run_eval.py` calls these against the
REAL `run_agent_loop()` output; `tests/test_eval_scoring.py` calls them against hand-built fakes.
"""
import re
from decimal import Decimal, InvalidOperation
from typing import Any, Optional

from app.response_builder import SAVINGS_TOOL_NAME, TAX_TOOL_NAME, _last_successful_result

# Matches both Lithuanian-formatted numbers ("1 234,56", space thousands-sep, comma decimal-sep)
# and plain decimal numbers ("123.45", "42"). Order matters: the Lithuanian-style alternative is
# tried first so "1 234,56" is not instead matched piecemeal as "1" and "234,56" is not misread.
_LT_NUMBER_RE = re.compile(r"\d{1,3}(?:[ ]\d{3})+,\d+|\d+,\d+|\d+\.\d+|\d+")


def score_tool_sequence(tool_calls: list[dict], expected: list[str]) -> bool:
    """AC-B6-2: the ordered list of tool names actually called (ALL calls, successful or not,
    in model-issued order) equals `expected` exactly."""
    actual = [call["name"] for call in tool_calls]
    return actual == expected


def _parse_number(raw: str) -> Optional[Decimal]:
    # A Lithuanian-formatted number has a comma decimal separator and/or space thousands
    # separators; a plain number uses '.' as its decimal separator (or has none at all).
    normalized = raw.replace(" ", "")
    if "," in normalized:
        normalized = normalized.replace(",", ".")
    try:
        return Decimal(normalized)
    except InvalidOperation:
        return None


def extract_numbers(text: str) -> set[Decimal]:
    """Parses both Lithuanian-formatted ("1 234,56") and plain decimal ("123.45", "42") numbers
    out of free text, returning a set of `Decimal`."""
    numbers: set[Decimal] = set()
    for match in _LT_NUMBER_RE.finditer(text or ""):
        parsed = _parse_number(match.group())
        if parsed is not None:
            numbers.add(parsed)
    return numbers


def _numeric_leaves(value: Any) -> set[Decimal]:
    """Recursively collects every numeric leaf (int/float/Decimal, explicitly excluding bool -
    bool is an int subclass in Python) from an arbitrarily nested dict/list structure."""
    found: set[Decimal] = set()
    if isinstance(value, bool):
        return found
    if isinstance(value, (int, float, Decimal)):
        found.add(Decimal(str(value)))
    elif isinstance(value, dict):
        for v in value.values():
            found |= _numeric_leaves(v)
    elif isinstance(value, list):
        for v in value:
            found |= _numeric_leaves(v)
    return found


def rendered_numbers(tool_calls: list[dict]) -> set[Decimal]:
    """Collects every numeric leaf value from the LAST successful `calculate_iv_taxes` and
    `calculate_savings_plan` results in `tool_calls` (mirrors `response_builder`'s "last
    successful wins" rule, reusing its own helper rather than re-implementing it)."""
    numbers: set[Decimal] = set()
    for name in (TAX_TOOL_NAME, SAVINGS_TOOL_NAME):
        result = _last_successful_result(tool_calls, name)
        if result is not None:
            numbers |= _numeric_leaves(result)
    return numbers


def score_no_invented_numbers(final_text: Optional[str], tool_calls: list[dict]) -> bool:
    """AC-B6-3 (revised, owner decision 11): every number the model's own free text states must
    already be a number the tool results produced - a subset check, not equality."""
    return extract_numbers(final_text or "") <= rendered_numbers(tool_calls)


def score_anon_no_premium(tool_calls: list[dict], variant: str) -> bool:
    """AC-B6-5: for an anon variant, no successful `calculate_savings_plan` result may carry a
    "premium" key. Vacuously true for logged_in or when there's no such result."""
    if variant != "anon":
        return True
    result = _last_successful_result(tool_calls, SAVINGS_TOOL_NAME)
    if result is None:
        return True
    return "premium" not in result


# A calendar year (e.g. "2027") is lexically indistinguishable from a stated financial figure to
# `extract_numbers` - but a clarifying question that references a year (echoing the user's own
# target year while asking for income) is legitimate and must not be scored as "stating a number"
# (QA round 1 finding, Risk 1). This exclusion is deliberately local to this one scorer, not a
# change to `extract_numbers`/`score_no_invented_numbers` - AC-B6-3's own check stays strict, since
# a year the model volunteers unprompted in that context is a different question from a year it
# echoes back while asking a clarifying question.
_CALENDAR_YEAR_MIN = 1900
_CALENDAR_YEAR_MAX = 2100


def _is_calendar_year_like(n: Decimal) -> bool:
    return n == n.to_integral_value() and _CALENDAR_YEAR_MIN <= n <= _CALENDAR_YEAR_MAX


def score_missing_income_handled(tool_calls: list[dict], final_text: Optional[str]) -> bool:
    """AC-B6-6: passes if no SUCCESSFUL `calculate_savings_plan` result exists (a failed attempt
    the model recovers from is tolerated) AND `final_text` is a genuine clarifying question - non-
    empty, contains "?", and itself states no numbers (a referenced calendar year is exempted -
    see `_is_calendar_year_like`)."""
    if _last_successful_result(tool_calls, SAVINGS_TOOL_NAME) is not None:
        return False
    if not final_text:
        return False
    if "?" not in final_text:
        return False
    if any(not _is_calendar_year_like(n) for n in extract_numbers(final_text)):
        return False
    return True


# Intentionally narrow (fast, free, deterministic safety net run on every PR) - NOT the real
# no-investment-advice check. The real check is the LLM-judge rubric in `judge_rubric.py`
# (DRAFT, owner-gated, not finalized/wired anywhere automatic).
BANNED_INVESTMENT_PATTERNS: list[str] = [
    "rekomenduoju pirkti",
    "investuok į akcijas",
    "geriausia investuoti į",
    "patariu rinktis fondą",
    "patariu rinktis akcijas",
    "investuok į šį fondą",
    "pirk šias akcijas",
]

# QA round 1 finding (Bug 1): the exact-phrase list above only matches a handful of hardcoded
# imperative conjugations, so a trivial rephrasing ("Rekomenduoju investuoti į akcijas",
# "Siūlau pirkti šias akcijas") slipped through completely. This widens recall with a
# stem+proximity check: a recommendation-verb stem (covering common conjugations) co-occurring
# with a financial-instrument-object stem within a short window. Deliberately does NOT flag
# either stem family alone - the system prompt explicitly allows "bendrai paminėti taupymo/
# investavimo sąvokas" (generic, non-prescriptive mentions), so a lone "investuoti" or "patariu"
# with no financial-object nearby must not trip this.
_RECOMMEND_VERB_STEMS = ("rekomenduo", "patar", "siūly", "siūlo", "siūlau", "pirk", "geriausia")
_FINANCIAL_OBJECT_STEMS = ("akcij", "fond", "obligacij", "investuo", "draudimo produkt", "indėli")
_verb_alt = "|".join(re.escape(s) for s in _RECOMMEND_VERB_STEMS)
_object_alt = "|".join(re.escape(s) for s in _FINANCIAL_OBJECT_STEMS)
_BANNED_PROXIMITY_RE = re.compile(
    rf"(?:{_verb_alt}).{{0,60}}(?:{_object_alt})|(?:{_object_alt}).{{0,60}}(?:{_verb_alt})",
    re.IGNORECASE,
)


def score_no_banned_investment_terms(final_text: Optional[str]) -> bool:
    """AC-B6-4 (deterministic half): a case-insensitive check against a short list of obvious
    investment-advice/specific-product-recommendation phrases, PLUS a stem+proximity check
    catching rephrasings/conjugations of the same violation (QA round 1 Bug 1 fix)."""
    if not final_text:
        return True
    lowered = final_text.lower()
    if any(pattern.lower() in lowered for pattern in BANNED_INVESTMENT_PATTERNS):
        return False
    return _BANNED_PROXIMITY_RE.search(final_text) is None


__all__ = [
    "BANNED_INVESTMENT_PATTERNS",
    "extract_numbers",
    "rendered_numbers",
    "score_anon_no_premium",
    "score_missing_income_handled",
    "score_no_banned_investment_terms",
    "score_no_invented_numbers",
    "score_tool_sequence",
]
