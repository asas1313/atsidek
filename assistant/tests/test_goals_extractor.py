"""Vienetiniai testai normalizavimo logikai (parse/_resolve_term/_amount/...), BE tikro Claude
kvietimo - tikrina, kad "20 tūkst."->20000, "pilnametis"->18, "iki vasaros"/"po 5 metų"->data
skaičiavimas vyksta kode, ne modelio prompte (spec 7 sk.)."""
from datetime import date
from decimal import Decimal

from app.goals_extractor import build_clarifying_question, missing_critical_fields, parse

TODAY = date(2026, 11, 10)


def full_confidence(**overrides) -> dict:
    fields = ["goalType", "mode", "amount", "years", "targetYear", "targetMonth", "childCurrentAge",
              "childTargetAge", "monthlyContribution", "members", "workers", "income", "expenses"]
    base = {f: 0.9 for f in fields}
    base.update(overrides)
    return base


def tool_input(**overrides) -> dict:
    base = {
        "goalType": "CHILD", "mode": "YEARS", "amountValue": None, "amountUnit": "EUR",
        "years": None, "relativeYearsFromNow": None, "targetYear": None, "targetMonth": None,
        "relativeSeason": None, "childCurrentAge": None, "childTargetAge": None,
        "childTargetIsAdult": False, "monthlyContribution": None, "members": None, "workers": None,
        "income": None, "expenses": None, "confidence": full_confidence(),
    }
    base.update(overrides)
    return base


def test_thousand_unit_is_multiplied_in_code():
    x = parse(tool_input(amountValue=20, amountUnit="THOUSAND_EUR"), TODAY)
    assert x.amount == Decimal("20000")


def test_plain_eur_unit_is_not_multiplied():
    x = parse(tool_input(amountValue=850, amountUnit="EUR"), TODAY)
    assert x.amount == Decimal("850")


def test_adult_phrase_maps_to_18():
    x = parse(tool_input(childTargetIsAdult=True, childTargetAge=None), TODAY)
    assert x.child_target_age == 18


def test_explicit_child_target_age_kept_when_not_adult_phrase():
    x = parse(tool_input(childTargetIsAdult=False, childTargetAge=10), TODAY)
    assert x.child_target_age == 10


def test_relative_years_used_for_years_mode():
    x = parse(tool_input(mode="YEARS", years=None, relativeYearsFromNow=5), TODAY)
    assert x.years == 5


def test_explicit_years_wins_over_relative():
    x = parse(tool_input(mode="YEARS", years=7, relativeYearsFromNow=2), TODAY)
    assert x.years == 7


def test_relative_years_from_now_resolved_to_absolute_year_for_date_mode():
    x = parse(tool_input(mode="DATE", relativeYearsFromNow=5), TODAY)
    assert x.target_year == 2031 and x.target_month is None


def test_season_before_current_month_resolves_to_next_year():
    # TODAY = 2026-11-10 (lapkritis); "iki vasaros" (birželis) šiais metais jau praėjęs -> kiti metai
    x = parse(tool_input(mode="DATE", relativeSeason="SUMMER"), TODAY)
    assert (x.target_year, x.target_month) == (2027, 6)


def test_season_after_current_month_resolves_to_same_year():
    # "iki žiemos" (gruodis) nuo lapkričio - tais pačiais metais
    x = parse(tool_input(mode="DATE", relativeSeason="WINTER"), TODAY)
    assert (x.target_year, x.target_month) == (2026, 12)


def test_explicit_target_year_wins_over_relative_and_season():
    x = parse(tool_input(mode="DATE", targetYear=2030, relativeYearsFromNow=5, relativeSeason="SUMMER"), TODAY)
    assert x.target_year == 2030


def test_invalid_target_month_dropped():
    x = parse(tool_input(mode="DATE", targetYear=2030, targetMonth=13), TODAY)
    assert x.target_month is None


def test_unknown_goal_type_and_mode_become_none():
    x = parse(tool_input(goalType="NOT_A_TYPE", mode="NOT_A_MODE"), TODAY)
    assert x.goal_type is None and x.mode is None


def test_bool_is_not_coerced_as_number_or_int():
    x = parse(tool_input(amountValue=True, years=True), TODAY)
    assert x.amount is None and x.years is None


def test_confidence_clamped_to_0_1_and_missing_fields_default_to_zero():
    x = parse(tool_input(confidence={"amount": 5, "mode": -2}), TODAY)
    assert x.confidence["amount"] == 1.0 and x.confidence["mode"] == 0.0
    assert x.confidence["goalType"] == 0.0  # nepaminėta reikšmė confidence objekte -> 0


def test_missing_amount_is_critical_for_every_mode():
    x = parse(tool_input(mode="YEARS", years=5, amountValue=None), TODAY)
    assert "amount" in x.missing


def test_missing_term_field_depends_on_mode():
    x = parse(tool_input(mode="CHILD_AGE", amountValue=20000, childCurrentAge=4, childTargetAge=None,
                          childTargetIsAdult=False), TODAY)
    assert "childTargetAge" in x.missing and "childCurrentAge" not in x.missing


def test_low_confidence_on_critical_field_is_flagged_even_when_present():
    x = parse(tool_input(mode="YEARS", amountValue=20000, years=5,
                          confidence=full_confidence(years=0.3)), TODAY)
    assert "years" in x.missing


def test_non_critical_low_confidence_does_not_block():
    x = parse(tool_input(mode="YEARS", amountValue=20000, years=5,
                          confidence=full_confidence(income=0.1)), TODAY)
    assert x.missing == []


def test_missing_critical_fields_helper_matches_dataclass_missing():
    x = parse(tool_input(mode="WHEN", amountValue=10000, monthlyContribution=None), TODAY)
    assert missing_critical_fields(x) == x.missing == ["monthlyContribution"]


def test_clarifying_question_is_none_when_nothing_missing():
    assert build_clarifying_question([], "YEARS") is None


def test_clarifying_question_prioritizes_amount_first():
    q = build_clarifying_question(["years", "amount"], "YEARS")
    assert q["field"] == "amount" and 2 <= len(q["options"]) <= 4


def test_clarifying_question_falls_back_to_term_field():
    q = build_clarifying_question(["childTargetAge"], "CHILD_AGE")
    assert q["field"] == "childTargetAge"
