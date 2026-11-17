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

# Matches Lithuanian-formatted numbers (space-separated thousands, comma decimal separator, e.g.
# "3 243,28", "20 000") and plain decimal numbers ("123.45", "42"). Owner instruction (second B6
# live-eval round): the second live run's no_invented_numbers check failed 119/126 - the ORIGINAL
# regex required a thousands-grouped number to ALSO have a comma decimal part ("1 234,56"), so a
# whole-euro figure the model writes WITHOUT decimals ("20 000 €", a very common way to state a
# round sum) matched only its own alternatives piecemeal: "20" (one group) and "000"=0 (a second,
# separate group) - neither of which is the real rendered number 20000, so almost every reply
# mentioning a round thousands figure was flagged as "inventing" a number it never actually
# invented. Added a thousands-without-decimal alternative, and widened the separator character
# class to also accept U+00A0 (non-breaking space) and U+202F (narrow no-break space), both of
# which some LLM-generated/rendered text uses in place of a plain space for this exact grouping.
# Order matters: longer/more specific alternatives are tried first so "20 000,50" is not instead
# matched piecemeal.
_LT_NUMBER_RE = re.compile(
    r"\d{1,3}(?:[   ]\d{3})+,\d+"
    r"|\d{1,3}(?:[   ]\d{3})+"
    r"|\d+,\d+"
    r"|\d+\.\d+"
    r"|\d+"
)


def score_tool_sequence(tool_calls: list[dict], expected: list[str]) -> bool:
    """AC-B6-2: the ordered list of tool names actually called (ALL calls, successful or not,
    in model-issued order) equals `expected` exactly."""
    actual = [call["name"] for call in tool_calls]
    return actual == expected


def _parse_number(raw: str) -> Optional[Decimal]:
    # A Lithuanian-formatted number has a comma decimal separator and/or space thousands
    # separators (plain, non-breaking, or narrow non-breaking - see _LT_NUMBER_RE above); a plain
    # number uses '.' as its decimal separator (or has none at all).
    normalized = raw.replace(" ", "").replace(" ", "").replace(" ", "")
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


def tool_input_numbers(tool_calls: list[dict]) -> set[Decimal]:
    """Collects every numeric leaf value from the INPUT of every tool call (successful or not) -
    e.g. the model is allowed to restate a `revenue`/`monthly_expenses` figure it itself just sent
    to a tool, even before that tool's result comes back (owner instruction, second B6 live-eval
    round: allowed numbers = tool results UNION tool inputs union user message numbers union a
    small constants list)."""
    numbers: set[Decimal] = set()
    for call in tool_calls:
        numbers |= _numeric_leaves(call["input"])
    return numbers


# Owner instruction (second B6 live-eval round): a small, deliberately conservative allowlist of
# constants the model may state on its own without having gotten them from a tool or the user -
# e.g. "12 mėnesių per metus", "30 % išlaidų norma" stated as a general fact. Kept tiny so it can
# never become a loophole for a real money figure.
_SMALL_ALLOWED_CONSTANTS: frozenset = frozenset(Decimal(str(n)) for n in (0, 1, 2, 3, 12, 30, 100))


def score_no_invented_numbers(
    final_text: Optional[str], tool_calls: list[dict], user_message: str = ""
) -> bool:
    """AC-B6-3 (revised, owner decision 11; allowed-set widened second B6 live-eval round): every
    number the model's own free text states must already be one of: a number a tool RESULT
    produced, a number the model itself sent as a tool INPUT, a number the USER'S OWN message
    already stated (the model may legitimately echo it back), or a small constant from
    `_SMALL_ALLOWED_CONSTANTS` - a subset check, not equality."""
    allowed = (
        rendered_numbers(tool_calls)
        | tool_input_numbers(tool_calls)
        | extract_numbers(user_message or "")
        | _SMALL_ALLOWED_CONSTANTS
    )
    return extract_numbers(final_text or "") <= allowed


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


# Owner decision, 2026-11-17 (first real staging walkthrough) - REPLACES the old
# score_missing_income_handled (which required a clarifying question and FAILED if a successful
# savings_plan result existed at all): a goal with no income stated must now still get a
# successful calculate_savings_plan result back (monthly contribution/reach-date computed), just
# with the free-cash fit fields null and the deterministic income-invitation tip present - a
# question blocking the calculation is no longer correct behavior, see
# app/system_prompt.py and SavingsPlanService#generalTip (Java).
def score_income_omitted_handled(tool_calls: list[dict], user_message: str = "") -> bool:
    """Passes when a SUCCESSFUL `calculate_savings_plan` result exists with a computed
    `monthly_contribution`, `free_cash_monthly` (and therefore `share_of_free_cash_pct`/`fits`)
    null, and a `general_tip` that actually invites the user to provide income (contains
    "pajam") - i.e. the tool was called with income genuinely omitted, never guessed, and the
    model did not block the calculation behind a clarifying question."""
    result = _last_successful_result(tool_calls, SAVINGS_TOOL_NAME)
    if result is None:
        return False
    core = result.get("core") or {}
    if core.get("monthly_contribution") is None:
        return False
    if core.get("free_cash_monthly") is not None:
        return False
    tip = core.get("general_tip") or ""
    return "pajam" in tip.lower()


def score_no_hallucinated_income(tool_calls: list[dict], user_message: str = "") -> bool:
    """Owner decision, 2026-11-17 (real staging bug: the model computed goal_amount/term and
    passed the result off as income to a tool - see `app.errors.HallucinatedIncomeError`, the
    matching runtime guard this eval check mirrors). Fails if `calculate_savings_plan`'s
    `net_monthly_income` or `calculate_iv_taxes`'s `revenue` tool-call INPUT (not result) carries
    a number absent from the user's own message, tolerating the legitimate x12 monthly->annual
    conversion `calculate_iv_taxes`'s `revenue` is allowed to use (system_prompt.py's own
    documented rule, `score_tax_input_revenue_matches_expected`'s same convention)."""
    user_numbers = extract_numbers(user_message or "")
    for call in tool_calls:
        tool_input = call.get("input") or {}
        if call["name"] == SAVINGS_TOOL_NAME:
            candidate = tool_input.get("net_monthly_income")
            if candidate is None:
                continue
            if Decimal(str(candidate)) not in user_numbers:
                return False
        elif call["name"] == TAX_TOOL_NAME:
            candidate = tool_input.get("revenue")
            if candidate is None:
                continue
            candidate = Decimal(str(candidate))
            if candidate in user_numbers or (candidate / 12) in user_numbers:
                continue
            return False
    return True


# Owner instruction (2026-11-16, stat_defaults sprendimas #4): typical_amount is never auto-filled
# (goal.amount is a REQUIRED calculate_savings_plan schema field, same treatment as income) - a
# goal with no stated target amount must make the model ASK for it, not call the tool. Mirrors
# _is_income_request/score_missing_income_handled exactly, keyed on "sum" (covers suma/sumos/
# sumą/sumai) instead of "pajam", reusing the same request-stem vocabulary.
_AMOUNT_REQUEST_STEMS = ("reik", "pasaky", "nurody", "praš", "trūksta")


def _is_amount_request(final_text: str) -> bool:
    """True for a genuine clarifying question ("?" present) OR a direct, non-question amount
    request that both mentions a sum ("sum") and uses one of `_AMOUNT_REQUEST_STEMS` - merely
    mentioning a sum in passing does NOT count, only an actual request/need phrasing does."""
    if "?" in final_text:
        return True
    lowered = final_text.lower()
    if "sum" not in lowered:
        return False
    return any(stem in lowered for stem in _AMOUNT_REQUEST_STEMS)


def score_missing_amount_handled(
    tool_calls: list[dict], final_text: Optional[str], user_message: str = ""
) -> bool:
    """Owner instruction (2026-11-16, stat_defaults sprendimas #4): passes if no SUCCESSFUL
    `calculate_savings_plan` result exists (a failed attempt the model recovers from is tolerated)
    AND `final_text` is a genuine amount request (see `_is_amount_request`) that states no numbers
    other than a referenced calendar year or a number the USER'S OWN message already stated -
    same shape as `score_missing_income_handled`, for the goal-amount-missing case instead of the
    income-missing case.
    """
    if _last_successful_result(tool_calls, SAVINGS_TOOL_NAME) is not None:
        return False
    if not final_text:
        return False
    if not _is_amount_request(final_text):
        return False
    user_numbers = extract_numbers(user_message or "")
    for n in extract_numbers(final_text):
        if _is_calendar_year_like(n) or n in user_numbers:
            continue
        return False
    return True


# AC-B6-4 deterministic half - SIMPLIFIED per owner decision 2026-11-13, after review rounds 1-3
# each found a new regex-edge-case false positive/negative in a verb+proximity+advisor-exception
# heuristic (recommending the user consult a financial advisor vs. recommending a product, three
# rounds of the SAME underlying ambiguity re-surfacing in a new shape each time). The owner's
# call: stop chasing that ambiguity with regex. This check is now a PURE banned-noun search - any
# mention of a specific financial product/instrument or a named bank/investment platform fails,
# unconditionally, with NO verb stem, NO proximity window, and NO "consult an advisor" exception.
# The recommendation-vs-mere-mention distinction (e.g. "pasitarkite su finansų konsultantu" is
# ALLOWED, recommending a specific product/instrument/savings method is NOT) is explicitly the
# LLM-judge rubric's job (`judge_rubric.py`, owner-gated via --with-judge), not this check's - that rubric
# was updated accordingly to state this distinction explicitly. This trades recall for the
# removal of every regex edge case found in rounds 1-3: a purely informational, non-prescriptive
# mention of one of these nouns will also fail this fast/free deterministic gate (an accepted,
# intentional cost - the LLM-judge is the actual arbiter of whether a given mention is advice).
#
# Deliberately EXCLUDES 2nd/3rd pillar pension-accumulation vocabulary ("pensijų kaupimas",
# "II pakopa", "III pakopa") - those are normal TAX inputs this agent legitimately discusses (see
# `tax_tool.py`'s `pension_accumulation` field), not investment-advice territory.
BANNED_INVESTMENT_NOUN_STEMS: tuple[str, ...] = (
    # Specific financial products/instruments (LT + EN forms; simple stems cover declensions).
    "fond",  # fondas/fondo/fondą/fondai/fondų (fund/funds)
    "akcij",  # akcija/akcijos/akcijas/akcijų (share/shares, stock/stocks)
    "obligacij",  # obligacija/obligacijos/obligacijas/obligacijų (bond/bonds)
    "etf",
    "kriptovaliut",  # kriptovaliuta/kriptovaliutos/kriptovaliutų (cryptocurrency)
    "bitkoin",
    "bitcoin",
    "indėli",  # indėlis/indėlio/indėliai/indėlių (deposit/deposits)
    "investicinis gyvybės draudim",  # investicinis/investicinio gyvybės draudimas (investment life insurance)
    # Named banks / investment platforms.
    "swedbank",
    "seb",
    "luminor",
    "šiaulių bank",
    "medicinos bank",
    "revolut",
    "etoro",
    "trading212",
    "trading 212",
    "interactive brokers",
    "invl",
)
_BANNED_NOUN_RE = re.compile(
    "|".join(re.escape(s) for s in BANNED_INVESTMENT_NOUN_STEMS), re.IGNORECASE
)


def score_no_banned_investment_terms(final_text: Optional[str]) -> bool:
    """AC-B6-4 (deterministic half, simplified 2026-11-13, stem list unchanged by the SAME-day
    stricter rubric tightening - see `judge_rubric.py`): fails if `final_text` mentions ANY
    specific financial product/instrument or named bank/investment-platform - a pure, unconditional
    noun search, no verb/proximity/context heuristics, and crucially NO advisor-referral exception
    (there never was one here - this scorer always failed on a bare noun regardless of context,
    which is exactly what the judge rubric's own stricter "no exception for an advisor referral"
    rule now also states, in prose, for its own broader category-level judgment). Whether a given
    mention is a genuine recommendation vs. a neutral reference is the LLM-judge rubric's job, not
    this one's - this check only ever asks "does this specific noun appear at all"."""
    if not final_text:
        return True
    return _BANNED_NOUN_RE.search(final_text) is None


def score_tax_input_revenue_matches_expected(
    tool_calls: list[dict], expected_annual_revenue: Optional[Decimal]
) -> bool:
    """New AC (2026-11-13 owner instruction): when a case states a MONTHLY IV income figure, the
    model must annualize it (x12) itself before calling `calculate_iv_taxes` - the tool has no
    "monthly" input mode, so a model that forgets to annualize would pass the raw monthly figure
    as if it were the yearly `revenue`. Checks the tool CALL'S OWN INPUT (`call["input"]["revenue"]`
    - what the model actually sent the tool), not the tool's result, against the case's expected
    annual figure. Looks at the FIRST `calculate_iv_taxes` call (mirrors how a single-shot
    annualization mistake would show up immediately, not just on a later retry). Vacuously True
    when `expected_annual_revenue` is None (the case doesn't apply - not a monthly-income case)."""
    if expected_annual_revenue is None:
        return True
    for call in tool_calls:
        if call["name"] == TAX_TOOL_NAME:
            revenue = call["input"].get("revenue")
            if revenue is None:
                return False
            try:
                return Decimal(str(revenue)) == Decimal(str(expected_annual_revenue))
            except InvalidOperation:
                return False
    return False


def score_savings_input_years_matches_expected(
    tool_calls: list[dict], expected_years: Optional[int]
) -> bool:
    """B8 item 2: a goal term stated as a DURATION ("per X metus"/"per X metų"/"po X metų", any
    case) must map to `calculate_savings_plan`'s `goal.mode="YEARS"` with `goal.years=X` - not be
    confused with an absolute target year (`goal.mode="DATE"`). Checks the FIRST
    `calculate_savings_plan` call's own INPUT (same convention as
    `score_tax_input_revenue_matches_expected`). Vacuously True when `expected_years` is None (the
    case doesn't apply)."""
    if expected_years is None:
        return True
    for call in tool_calls:
        if call["name"] == SAVINGS_TOOL_NAME:
            goal = call["input"].get("goal") or {}
            return goal.get("mode") == "YEARS" and goal.get("years") == expected_years
    return False


def score_response_complete(stop_reason: Optional[str]) -> bool:
    """Owner instruction (2026-11-16, after eval a2dbaae, item 3): fails ONLY when the agent's own
    final text turn was cut off by the token budget (`agent_loop.AgentLoopResult.stop_reason ==
    "max_tokens"`) - any other stop_reason (`"end_turn"`, `None` from a test fake or a
    `max_iterations_exceeded` run which has no real "final" turn) passes."""
    return stop_reason != "max_tokens"


__all__ = [
    "BANNED_INVESTMENT_NOUN_STEMS",
    "extract_numbers",
    "rendered_numbers",
    "score_anon_no_premium",
    "score_income_omitted_handled",
    "score_missing_amount_handled",
    "score_no_banned_investment_terms",
    "score_no_hallucinated_income",
    "score_no_invented_numbers",
    "score_response_complete",
    "score_savings_input_years_matches_expected",
    "score_tax_input_revenue_matches_expected",
    "score_tool_sequence",
    "tool_input_numbers",
]
