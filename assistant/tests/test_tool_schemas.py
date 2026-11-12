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
