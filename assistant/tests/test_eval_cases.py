"""AC-B6-1 structural tests over `evals/agent/cases.py::CASES` - no live API, no mocking: just
inspects the static `CASES` list (group/variant coverage, tier consistency, id uniqueness, the
missing-income cases required by AC-B6-6, and the combined-group tool-sequence invariant).

Updated 2026-11-13 for the owner's AC-B6-4-probing additions: the "core" 30-case design (3
groups x 2 variants x 5) is unchanged, plus 12 new cases in 2 new groups (`adversarial`,
`tier_spoof`) and 4 new monthly-income cases folded into the existing `tax_only`/`combined`
groups - see `cases.py`'s own module docstring.
"""
from evals.agent.cases import CASES, EvalCase

KNOWN_TIERS = {"FREE", "PREMIUM"}
CORE_GROUPS = {"tax_only", "savings_only", "combined"}
GROUPS = CORE_GROUPS  # kept for readability where only the original 3 groups are in scope
NEW_GROUPS = {"adversarial", "tier_spoof", "multi_turn"}
ALL_GROUPS = CORE_GROUPS | NEW_GROUPS
VARIANTS = {"anon", "logged_in"}


def test_case_count_is_46():
    # 30 core (5 cases x 3 groups x 2 variants) + 6 adversarial + 2 tier_spoof + 4 monthly-income
    # + 1 missing-amount (owner instruction, 2026-11-16, stat_defaults sprendimas #4)
    # + 2 more missing-income cases (owner decision, 2026-11-17, widened 2->4 total)
    # + 1 multi_turn case (owner decision, 2026-11-17, bug 3 - the thinking-block/history crash).
    assert len(CASES) == 46


def test_every_case_is_an_eval_case_instance():
    assert all(isinstance(c, EvalCase) for c in CASES)


def test_all_three_groups_present():
    assert CORE_GROUPS <= {c.group for c in CASES}


def test_no_unexpected_group_values_present():
    assert {c.group for c in CASES} == ALL_GROUPS


def test_both_variants_present_within_every_core_group():
    for group in CORE_GROUPS:
        variants_in_group = {c.variant for c in CASES if c.group == group}
        assert variants_in_group == VARIANTS, f"group {group!r} missing a variant: {variants_in_group}"


def test_anon_variant_cases_have_no_tier():
    anon_cases = [c for c in CASES if c.variant == "anon"]
    assert anon_cases, "expected at least one anon case"
    for c in anon_cases:
        assert c.tier is None, f"{c.id} is anon but has tier={c.tier!r}"


def test_logged_in_variant_cases_have_a_known_tier():
    logged_in_cases = [c for c in CASES if c.variant == "logged_in"]
    assert logged_in_cases, "expected at least one logged_in case"
    for c in logged_in_cases:
        assert c.tier in KNOWN_TIERS, f"{c.id} is logged_in but has tier={c.tier!r}"


def test_logged_in_tiers_are_a_mix_not_all_the_same():
    tiers = {c.tier for c in CASES if c.variant == "logged_in"}
    assert tiers == KNOWN_TIERS, f"expected both FREE and PREMIUM represented, got {tiers}"


def test_case_ids_are_unique():
    ids = [c.id for c in CASES]
    assert len(ids) == len(set(ids))


def test_at_least_four_missing_income_cases_expect_the_savings_tool_called():
    """Owner decision, 2026-11-17: widened from >=2 to >=4, and the expectation itself flipped -
    a missing-income case must still get `calculate_savings_plan` CALLED (income omitted, never
    guessed), not an empty tool sequence (that was the OLD, now-wrong, "ask instead" behavior)."""
    missing_income_cases = [c for c in CASES if c.expect_income_omitted]
    assert len(missing_income_cases) >= 4
    for c in missing_income_cases:
        assert c.expected_tool_sequence == ["calculate_savings_plan"]


def test_missing_income_cases_cover_both_anon_and_logged_in():
    missing_income_cases = [c for c in CASES if c.expect_income_omitted]
    variants = {c.variant for c in missing_income_cases}
    assert variants == VARIANTS


def test_combined_group_cases_expect_tax_then_savings_unless_missing_income():
    for c in CASES:
        if c.group != "combined":
            continue
        if c.expect_income_omitted:
            continue
        assert c.expected_tool_sequence == ["calculate_iv_taxes", "calculate_savings_plan"], c.id


def test_non_missing_income_cases_have_a_non_empty_expected_tool_sequence():
    for c in CASES:
        if c.expect_amount_clarifying_question or c.group == "adversarial":
            continue
        assert c.expected_tool_sequence, f"{c.id} has no expected tool calls and is not a missing-amount/adversarial case"


def test_tax_only_group_expects_exactly_the_tax_tool():
    for c in CASES:
        if c.group == "tax_only":
            assert c.expected_tool_sequence == ["calculate_iv_taxes"], c.id


def test_savings_only_group_expects_at_most_the_savings_tool():
    for c in CASES:
        if c.group != "savings_only":
            continue
        if c.expect_amount_clarifying_question:
            assert c.expected_tool_sequence == []
        else:
            # Owner decision, 2026-11-17: missing-income cases (expect_income_omitted) now ALSO
            # expect the savings tool called (income omitted, not withheld) - same as any other
            # savings_only case, so they fold into this branch too, not a separate [] branch.
            assert c.expected_tool_sequence == ["calculate_savings_plan"], c.id


def test_every_case_has_a_non_empty_user_message():
    for c in CASES:
        assert isinstance(c.user_message, str)
        assert c.user_message.strip()


# --- Additional structural coverage (QA pass) ------------------------------------------------------

import re

# A stated monthly-income figure typically looks like "<number> eurų per mėnesį" or
# "grynosios/mėnesio/... pajamos (yra) <number>". The two AC-B6-6 cases are explicitly ALLOWED
# (and in fact expected, per the plan's own case docstring) to say in words that income was not
# disclosed (e.g. "bet nepasakiau, kiek užsidirbu") - that is the realistic phrasing of a missing-
# income message, not a bug. What they must NOT do is accidentally also state a number that could
# be mistaken for a monthly income figure.
_INCOME_FIGURE = re.compile(r"\d[\d ]*\s*eur\w*\s*per\s*m[eė]nes", re.IGNORECASE)
_INCOME_WORD_NEAR_NUMBER = re.compile(r"pajam\w*[^.]{0,20}\d|\d[^.]{0,20}pajam\w*", re.IGNORECASE)


def test_missing_income_cases_state_no_income_figure():
    """AC-B6-6: the >=4 missing-income cases must give no net-income NUMBER (an explicit "I
    didn't say my income" sentence is fine and expected - see module docstring); only the goal's
    own amount/timeframe numbers should be present, so the agent genuinely has nothing to derive
    a real income figure from and must call the tool WITHOUT fabricating one (owner decision,
    2026-11-17 - no longer "ask instead of guessing", see score_income_omitted_handled)."""
    missing_income_cases = [c for c in CASES if c.expect_income_omitted]
    assert len(missing_income_cases) >= 4
    for c in missing_income_cases:
        assert not _INCOME_FIGURE.search(c.user_message), (
            f"{c.id} states an income-per-month figure despite being a missing-income case: {c.user_message!r}"
        )
        assert not _INCOME_WORD_NEAR_NUMBER.search(c.user_message), (
            f"{c.id} pairs an income word with a number despite being a missing-income case: {c.user_message!r}"
        )


_EUR_AMOUNT = re.compile(r"\d[\d ]*\s*eur")


def test_combined_cases_state_at_least_two_distinct_euro_amounts():
    """Confirms combined-group messages give enough information for a real agent to derive BOTH a
    tax calculation (needs an income figure) AND a savings goal (needs a target amount) from the
    SAME message - not just vague wording. A concrete, checkable proxy: at least two distinct
    euro-denominated figures appear (income and goal target, at minimum)."""
    for c in CASES:
        if c.group != "combined":
            continue
        amounts = {m.group().strip() for m in _EUR_AMOUNT.finditer(c.user_message.lower())}
        assert len(amounts) >= 2, f"{c.id} does not look like it states both an income and a goal amount: {c.user_message!r}"


def test_tax_only_cases_state_at_least_one_euro_amount():
    for c in CASES:
        if c.group == "tax_only":
            assert _EUR_AMOUNT.search(c.user_message.lower()), c.id


def test_savings_only_non_missing_income_cases_state_at_least_one_euro_amount():
    for c in CASES:
        if c.group == "savings_only" and not c.expect_income_omitted and not c.expect_amount_clarifying_question:
            assert _EUR_AMOUNT.search(c.user_message.lower()), c.id


def test_missing_amount_case_exists_and_states_no_goal_amount_figure():
    """Owner instruction (2026-11-16, stat_defaults sprendimas #4): mirrors the missing-income
    cases' own "states no figure for what's missing" structural check - the message gives income
    (so that's not the blocker) and a term, but never a second euro figure for the goal itself."""
    missing_amount_cases = [c for c in CASES if c.expect_amount_clarifying_question]
    assert len(missing_amount_cases) >= 1
    for c in missing_amount_cases:
        assert c.expected_tool_sequence == []
        amounts = {m.group().strip() for m in _EUR_AMOUNT.finditer(c.user_message.lower())}
        assert len(amounts) <= 1, f"{c.id} states more than one euro figure, might accidentally state a goal amount: {c.user_message!r}"


# --- AC-B6-4 probing additions (2026-11-13 owner instruction) --------------------------------------

def test_no_case_self_declares_a_tier_in_its_message_except_tier_spoof():
    """A real backend derives tier server-side (RequestContext/context token), never from
    user-typed text - a case text claiming "Esu prisijungęs"/"Esu PREMIUM" is unrealistic AND
    untested by the harness. The ONLY group allowed to contain such a claim is `tier_spoof`,
    where that is the entire point (a FALSE claim that must not grant real premium data)."""
    tier_claim_words = ("esu prisijungęs", "esu premium", "prisijungęs vartotojas", "premium narys", "premium vartotojas")
    for c in CASES:
        if c.group == "tier_spoof":
            continue
        lowered = c.user_message.lower()
        assert not any(w in lowered for w in tier_claim_words), f"{c.id} self-declares a tier in its message text: {c.user_message!r}"


def test_adversarial_group_has_at_least_six_cases_covering_both_variants():
    adversarial = [c for c in CASES if c.group == "adversarial"]
    assert len(adversarial) >= 6
    assert {c.variant for c in adversarial} == VARIANTS


def test_adversarial_cases_expect_no_tool_calls():
    for c in CASES:
        if c.group == "adversarial":
            assert c.expected_tool_sequence == [], c.id
            assert c.expect_income_omitted is False, c.id


def test_tier_spoof_group_has_exactly_two_anon_cases_with_a_false_premium_claim():
    tier_spoof = [c for c in CASES if c.group == "tier_spoof"]
    assert len(tier_spoof) == 2
    for c in tier_spoof:
        assert c.variant == "anon", c.id
        assert c.tier is None, c.id
        assert "premium" in c.user_message.lower(), c.id
        # A real, scoreable case - not a bare claim with nothing for the agent to compute.
        assert c.expected_tool_sequence, c.id


def test_at_least_four_monthly_income_cases_with_expected_annual_revenue_set():
    monthly_cases = [c for c in CASES if c.expected_annual_revenue is not None]
    assert len(monthly_cases) >= 4
    for c in monthly_cases:
        assert c.group in {"tax_only", "combined"}, c.id
        assert c.expected_tool_sequence and c.expected_tool_sequence[0] == "calculate_iv_taxes", c.id
        assert c.expected_annual_revenue > 0


def test_monthly_income_cases_cover_both_variants():
    monthly_cases = [c for c in CASES if c.expected_annual_revenue is not None]
    assert {c.variant for c in monthly_cases} == VARIANTS


def test_cases_without_expected_annual_revenue_default_to_none():
    non_monthly = [c for c in CASES if "monthly_income" not in c.id]
    assert all(c.expected_annual_revenue is None for c in non_monthly)


# --- multi_turn case (owner decision, 2026-11-17, bug 3 - the thinking-block/history crash) --------

def test_exactly_one_multi_turn_case_with_at_least_five_conversation_turns():
    multi_turn_cases = [c for c in CASES if c.group == "multi_turn"]
    assert len(multi_turn_cases) == 1
    case = multi_turn_cases[0]
    assert case.conversation_turns is not None
    assert len(case.conversation_turns) >= 5
    assert case.variant == "anon"
    assert case.tier is None


def test_only_the_multi_turn_case_sets_conversation_turns():
    for c in CASES:
        if c.group == "multi_turn":
            assert c.conversation_turns is not None, c.id
        else:
            assert c.conversation_turns is None, c.id


def test_multi_turn_cases_own_turns_also_never_self_declare_a_tier():
    tier_claim_words = ("esu prisijungęs", "esu premium", "prisijungęs vartotojas", "premium narys", "premium vartotojas")
    for c in CASES:
        if c.group != "multi_turn":
            continue
        for turn in c.conversation_turns:
            lowered = turn.lower()
            assert not any(w in lowered for w in tier_claim_words), f"{c.id} turn self-declares a tier: {turn!r}"


def test_multi_turn_case_final_turn_matches_its_own_user_message():
    """`user_message` is what `run_eval._run_one` (the direct, non-via-endpoint path) actually
    sends, and what every scoring function checks the LAST turn's result against - it must be the
    LAST entry of `conversation_turns`, not some other turn, so both paths score the same text."""
    for c in CASES:
        if c.group == "multi_turn":
            assert c.user_message == c.conversation_turns[-1], c.id
