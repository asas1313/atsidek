"""AC-B6-1 structural tests over `evals/agent/cases.py::CASES` - no live API, no mocking: just
inspects the static `CASES` list (group/variant coverage, tier consistency, id uniqueness, the
missing-income cases required by AC-B6-6, and the combined-group tool-sequence invariant).
"""
from evals.agent.cases import CASES, EvalCase

KNOWN_TIERS = {"FREE", "PREMIUM"}
GROUPS = {"tax_only", "savings_only", "combined"}
VARIANTS = {"anon", "logged_in"}


def test_case_count_is_close_to_30():
    # Exactly 30 (5 cases x 3 groups x 2 variants) - the plan's own target, hit exactly here.
    assert len(CASES) == 30


def test_every_case_is_an_eval_case_instance():
    assert all(isinstance(c, EvalCase) for c in CASES)


def test_all_three_groups_present():
    assert {c.group for c in CASES} == GROUPS


def test_both_variants_present_within_every_group():
    for group in GROUPS:
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


def test_at_least_two_missing_income_cases_with_no_expected_tool_calls():
    missing_income_cases = [c for c in CASES if c.expect_clarifying_question]
    assert len(missing_income_cases) >= 2
    for c in missing_income_cases:
        assert c.expected_tool_sequence == []


def test_missing_income_cases_cover_both_anon_and_logged_in():
    missing_income_cases = [c for c in CASES if c.expect_clarifying_question]
    variants = {c.variant for c in missing_income_cases}
    assert variants == VARIANTS


def test_combined_group_cases_expect_tax_then_savings_unless_missing_income():
    for c in CASES:
        if c.group != "combined":
            continue
        if c.expect_clarifying_question:
            continue
        assert c.expected_tool_sequence == ["calculate_iv_taxes", "calculate_savings_plan"], c.id


def test_non_missing_income_cases_have_a_non_empty_expected_tool_sequence():
    for c in CASES:
        if c.expect_clarifying_question:
            continue
        assert c.expected_tool_sequence, f"{c.id} has no expected tool calls and is not a missing-income case"


def test_tax_only_group_expects_exactly_the_tax_tool():
    for c in CASES:
        if c.group == "tax_only":
            assert c.expected_tool_sequence == ["calculate_iv_taxes"], c.id


def test_savings_only_group_expects_at_most_the_savings_tool():
    for c in CASES:
        if c.group != "savings_only":
            continue
        if c.expect_clarifying_question:
            assert c.expected_tool_sequence == []
        else:
            assert c.expected_tool_sequence == ["calculate_savings_plan"], c.id


def test_every_case_has_a_non_empty_user_message():
    for c in CASES:
        assert isinstance(c.user_message, str)
        assert c.user_message.strip()
