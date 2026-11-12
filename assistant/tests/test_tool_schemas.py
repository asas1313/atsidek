"""AC-B0-2: Pydantic validates the existing /asistentas extraction tool's input (the dict Claude
returns via tool_use), proving the plumbing against real, already-tested behaviour (parse()).
"""
import pytest
from pydantic import ValidationError

from app.extractor import IvExtractionToolInput, parse

VALID = {
    "pajamos_eur": 2400,
    "islaidos_eur": None,
    "islaidu_budas": "faktines",
    "laikotarpis": "",
    "kitos_pajamos_eur": 12000.5,
    "pensiju_kaupimas": True,
    "psd_menesiai": 6,
    "trukstama_info": ["išlaidos"],
}


def test_valid_payload_parses():
    model = IvExtractionToolInput.model_validate(VALID)
    assert model.pajamos_eur == 2400
    assert model.islaidu_budas == "faktines"
    assert model.trukstama_info == ["išlaidos"]


def test_valid_payload_feeds_the_existing_untouched_parse_function():
    model = IvExtractionToolInput.model_validate(VALID)
    x = parse(model.model_dump())
    assert x.revenue == 2400 and x.pillar2 is True and x.psd_months == 6


def test_missing_required_field_raises_validation_error_with_field_name():
    bad = {k: v for k, v in VALID.items() if k != "pajamos_eur"}
    with pytest.raises(ValidationError) as exc:
        IvExtractionToolInput.model_validate(bad)
    assert any("pajamos_eur" in str(e["loc"]) for e in exc.value.errors())


def test_wrong_type_field_raises_validation_error_with_field_name():
    bad = {**VALID, "pajamos_eur": ["not", "a", "number"]}
    with pytest.raises(ValidationError) as exc:
        IvExtractionToolInput.model_validate(bad)
    assert any("pajamos_eur" in str(e["loc"]) for e in exc.value.errors())


def test_out_of_enum_value_raises_validation_error_with_field_name():
    bad = {**VALID, "islaidu_budas": "kazkas_kito"}
    with pytest.raises(ValidationError) as exc:
        IvExtractionToolInput.model_validate(bad)
    assert any("islaidu_budas" in str(e["loc"]) for e in exc.value.errors())


@pytest.mark.parametrize("field_name", ["pajamos_eur", "islaidos_eur", "kitos_pajamos_eur", "psd_menesiai"])
def test_bool_for_numeric_field_rejected_matching_parse(field_name):
    """Fixed per B0 review: the model's numeric fields reject bool values (via an explicit
    field_validator), matching `parse()`'s `_num()` semantics (see "bool yra int potipis -
    atmetam"), instead of pydantic v2's default silent bool -> number coercion (True -> 1.0).
    """
    payload = {**VALID, field_name: True}
    with pytest.raises(ValidationError) as exc:
        IvExtractionToolInput.model_validate(payload)
    assert any(field_name in str(e["loc"]) for e in exc.value.errors())
    result_attr = {
        "pajamos_eur": "revenue",
        "islaidos_eur": "expenses",
        "kitos_pajamos_eur": "other_income",
        "psd_menesiai": "psd_months",
    }[field_name]
    assert getattr(parse({**VALID, field_name: True}), result_attr) is None  # parse() still rejects it too


@pytest.mark.parametrize("bad_value", [6.0, "6", "6.0"])
def test_non_int_psd_menesiai_rejected_matching_parse(bad_value):
    """parse() requires `isinstance(months, int) and not isinstance(months, bool)`; a float or a
    numeric string must therefore be rejected here too, not silently coerced to 6 by pydantic.
    """
    payload = {**VALID, "psd_menesiai": bad_value}
    with pytest.raises(ValidationError) as exc:
        IvExtractionToolInput.model_validate(payload)
    assert any("psd_menesiai" in str(e["loc"]) for e in exc.value.errors())
    assert parse({**VALID, "psd_menesiai": bad_value}).psd_months is None  # parse() still rejects it too


@pytest.mark.parametrize("bad_value", [1, 0, "true", "yes"])
def test_non_bool_pensiju_kaupimas_rejected_matching_parse(bad_value):
    """parse() requires `isinstance(pillar, bool)`; ints and strings (even truthy ones like
    "true"/"yes") must therefore be rejected here too, not silently coerced by pydantic.
    """
    payload = {**VALID, "pensiju_kaupimas": bad_value}
    with pytest.raises(ValidationError) as exc:
        IvExtractionToolInput.model_validate(payload)
    assert any("pensiju_kaupimas" in str(e["loc"]) for e in exc.value.errors())
    assert parse({**VALID, "pensiju_kaupimas": bad_value}).pillar2 is None  # parse() still rejects it too
