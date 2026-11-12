"""AC-B4-1/AC-B4-2: `response_builder.build_response` is a deterministic template, not an
LLM-output test - every assertion here is about section ORDER and EXACT numeric equality given
fixed, hand-built `tool_calls` inputs (the same shape `agent_loop.AgentLoopResult.tool_calls`
produces), never about LLM-generated prose.
"""
from app.response_builder import build_response, pick_cta_feature


def tax_call(call_id="tu_tax", is_error=False, result=None):
    return {
        "id": call_id,
        "name": "calculate_iv_taxes",
        "input": {},
        "result": result,
        "error": None if not is_error else "boom",
        "is_error": is_error,
    }


def savings_call(call_id="tu_sav", is_error=False, result=None):
    return {
        "id": call_id,
        "name": "calculate_savings_plan",
        "input": {},
        "result": result,
        "error": None if not is_error else "boom",
        "is_error": is_error,
    }


TAX_RESULT = {
    "tax_calc_id": "abc123",
    "method": "ACTUAL",
    "total_tax_per_year": 1234.56,
    "monthly_tax_setaside": 102.88,
    "net_monthly_income": 987.65,
}

TAX_RESULT_FLAT = {
    "tax_calc_id": "abc124",
    "method": "FLAT_30",
    "total_tax_per_year": 2000,
    "monthly_tax_setaside": 166.67,
    "net_monthly_income": 1500,
}

ASSUMPTIONS = [
    {
        "field": "monthly_expenses",
        "value": 650.0,
        "origin": "STATISTICS",
        "source": "Sodra 2025",
        "source_date": "2025-01-01",
        "is_illustrative": True,
    },
    {
        "field": "annual_return_pct",
        "value": 3.5,
        "origin": "ASSUMPTION",
        "source": None,
        "source_date": None,
        "is_illustrative": None,
    },
]

SAVINGS_RESULT = {
    "tier": "FREE",
    "core": {
        "monthly_contribution": 200.0,
        "free_cash_monthly": 400.0,
        "share_of_free_cash_pct": 50.0,
        "fits": True,
        "general_tip": "Lieka rezervas netikėtoms išlaidoms.",
        "assumptions": ASSUMPTIONS,
        "disclaimer": "Šis skaičiavimas yra informacinio pobūdžio ir nėra finansinė konsultacija.",
    },
    "locked_features": ["SAVINGS_MULTI_GOAL", "SAVINGS_IV_LINK"],
    "income_origin": "TAX_CALC",
}

SAVINGS_RESULT_NO_ASSUMPTIONS_NO_LOCKS = {
    "tier": "PREMIUM",
    "core": {
        "monthly_contribution": 300.0,
        "free_cash_monthly": 500.0,
        "share_of_free_cash_pct": 60.0,
        "fits": True,
        "general_tip": "Viskas gerai.",
        "assumptions": [],
        "disclaimer": "Šis skaičiavimas yra informacinio pobūdžio ir nėra finansinė konsultacija.",
    },
    "locked_features": [],
    "income_origin": "USER",
}


# --- AC-B4-1: section ordering + exact numeric equality ----------------------------------------

def test_full_response_section_ordering_taxes_before_free_cash_before_goal_before_assumptions_before_disclaimer_before_cta():
    tool_calls = [tax_call(result=TAX_RESULT), savings_call(result=SAVINGS_RESULT)]

    text = build_response(tool_calls, None)

    idx_taxes = text.index("1 234,56")
    idx_free_cash = text.index("400")
    idx_goal = text.index("200")
    idx_assumptions = text.index("Mėnesio išlaidos")
    idx_disclaimer = text.index(SAVINGS_RESULT["core"]["disclaimer"])
    idx_cta = text.index("Tavo pajamos svyruoja")

    assert idx_taxes < idx_free_cash < idx_goal < idx_assumptions < idx_disclaimer < idx_cta


def test_tax_section_numbers_match_tool_result_exactly():
    tool_calls = [tax_call(result=TAX_RESULT)]

    text = build_response(tool_calls, None)

    assert "faktinės išlaidos" in text
    assert "1 234,56" in text  # total_tax_per_year
    assert "102,88" in text  # monthly_tax_setaside
    assert "987,65" in text  # net_monthly_income


def test_tax_section_flat_30_method_label():
    tool_calls = [tax_call(result=TAX_RESULT_FLAT)]

    text = build_response(tool_calls, None)

    assert "30 % išlaidų norma" in text


def test_goal_and_free_cash_section_numbers_match_tool_result_exactly():
    tool_calls = [savings_call(result=SAVINGS_RESULT)]

    text = build_response(tool_calls, None)

    assert "400,00" in text  # free_cash_monthly
    assert "200,00" in text  # monthly_contribution
    assert "50,0%" in text  # share_of_free_cash_pct, NOT multiplied again
    assert SAVINGS_RESULT["core"]["general_tip"] in text


def test_assumptions_section_renders_one_line_per_entry_with_origin_label():
    tool_calls = [savings_call(result=SAVINGS_RESULT)]

    text = build_response(tool_calls, None)

    assert "Mėnesio išlaidos" in text
    assert "650,00" in text
    assert "Statistika: Sodra 2025" in text
    assert "Metinė grąža" in text
    assert "3,5%" in text
    assert "Prielaida" in text


def test_disclaimer_rendered_verbatim():
    tool_calls = [savings_call(result=SAVINGS_RESULT)]

    text = build_response(tool_calls, None)

    assert SAVINGS_RESULT["core"]["disclaimer"] in text


def test_only_taxes_no_savings_call_renders_taxes_section_only_no_savings_sections():
    tool_calls = [tax_call(result=TAX_RESULT)]

    text = build_response(tool_calls, None)

    assert "1 234,56" in text
    assert "Mėnesio išlaidos" not in text
    assert SAVINGS_RESULT["core"]["disclaimer"] not in text


def test_only_savings_no_tax_call_renders_savings_sections_only_no_tax_section():
    tool_calls = [savings_call(result=SAVINGS_RESULT)]

    text = build_response(tool_calls, None)

    assert "faktinės išlaidos" not in text
    assert "30 % išlaidų norma" not in text
    assert "400,00" in text


def test_neither_tool_called_returns_final_text_alone():
    assert build_response([], "Sveika! Kuo galiu padėti?") == "Sveika! Kuo galiu padėti?"


def test_neither_tool_called_and_no_final_text_returns_empty_string():
    assert build_response([], None) == ""


def test_final_text_precedes_deterministic_sections_when_both_present():
    tool_calls = [tax_call(result=TAX_RESULT)]
    final_text = "Štai tavo mokesčių suvestinė:"

    text = build_response(tool_calls, final_text)

    assert text.index(final_text) < text.index("1 234,56")


def test_is_error_entry_is_ignored_last_successful_call_wins():
    tool_calls = [
        tax_call(call_id="tu1", result=TAX_RESULT),
        tax_call(call_id="tu2", is_error=True, result=None),
    ]

    text = build_response(tool_calls, None)

    assert "1 234,56" in text  # the successful result, not the later failed one


def test_last_successful_call_wins_when_multiple_successes_present():
    tool_calls = [
        tax_call(call_id="tu1", result=TAX_RESULT),
        tax_call(call_id="tu2", result=TAX_RESULT_FLAT),
    ]

    text = build_response(tool_calls, None)

    assert "30 % išlaidų norma" in text
    assert "faktinės išlaidos" not in text


def test_empty_assumptions_list_omits_assumptions_section():
    tool_calls = [savings_call(result=SAVINGS_RESULT_NO_ASSUMPTIONS_NO_LOCKS)]

    text = build_response(tool_calls, None)

    assert "Mėnesio išlaidos" not in text
    assert "Metinė grąža" not in text


def test_empty_locked_features_omits_cta_section():
    tool_calls = [savings_call(result=SAVINGS_RESULT_NO_ASSUMPTIONS_NO_LOCKS)]

    text = build_response(tool_calls, None)

    assert "prisijung" not in text.lower()


# --- AC-B4-2: pick_cta_feature ------------------------------------------------------------------

def test_pick_cta_feature_empty_list_returns_none():
    assert pick_cta_feature([]) is None


def test_pick_cta_feature_preferred_feature_wins_regardless_of_position():
    assert pick_cta_feature(["SAVINGS_MULTI_GOAL", "SAVINGS_IV_LINK", "SAVINGS_SCENARIOS"]) == "SAVINGS_IV_LINK"
    assert pick_cta_feature(["SAVINGS_IV_LINK", "SAVINGS_MULTI_GOAL"]) == "SAVINGS_IV_LINK"


def test_pick_cta_feature_without_preferred_feature_first_element_wins():
    assert pick_cta_feature(["SAVINGS_MULTI_GOAL", "SAVINGS_SCENARIOS"]) == "SAVINGS_MULTI_GOAL"
    assert pick_cta_feature(["SAVINGS_SCENARIOS"]) == "SAVINGS_SCENARIOS"


def test_cta_section_renders_exactly_one_sentence_for_preferred_feature():
    tool_calls = [savings_call(result=SAVINGS_RESULT)]

    text = build_response(tool_calls, None)

    assert text.count("prisijung") >= 1
    # Only the preferred feature's sentence appears, not the other locked feature's sentence.
    assert "daugiau nei vieną taupymo tikslą" not in text


# --- QA follow-up: empty inputs, defensive degradation, exact-number independence ---------------

def test_empty_tool_calls_and_empty_string_final_text_returns_empty_string():
    """Falsy-but-not-None `final_text` (e.g. "") must behave like `None` - still returns ""."""
    assert build_response([], "") == ""


def test_no_tool_calls_at_all_and_final_text_none_is_empty_string_not_none_or_crash():
    result = build_response([], None)
    assert result == ""
    assert result is not None


def test_last_successful_savings_call_wins_when_a_later_savings_call_fails():
    """AC-B4-1's 'last successful call wins, not last call overall', specifically for
    calculate_savings_plan (the existing coverage only exercised calculate_iv_taxes for this)."""
    tool_calls = [
        savings_call(call_id="s1", result=SAVINGS_RESULT),
        savings_call(call_id="s2", is_error=True, result=None),
    ]

    text = build_response(tool_calls, None)

    # The earlier, successful result's numbers are used - not a crash, not an empty savings
    # section from falling through to the later failed call.
    assert "400,00" in text  # free_cash_monthly from SAVINGS_RESULT
    assert "200,00" in text  # monthly_contribution from SAVINGS_RESULT


def test_mixed_tax_and_savings_calls_each_pick_their_own_last_successful_result_independently():
    """A failed LATER tax call must not blank out the savings section, and vice versa - the two
    tools' 'last successful' lookups are independent of each other."""
    tool_calls = [
        tax_call(call_id="t1", result=TAX_RESULT),
        savings_call(call_id="s1", result=SAVINGS_RESULT),
        tax_call(call_id="t2", is_error=True, result=None),
    ]

    text = build_response(tool_calls, None)

    assert "1 234,56" in text
    assert "400,00" in text


def test_final_text_numbers_are_not_mixed_into_or_used_for_deterministic_sections():
    """AC-B4-1: numbers come EXACTLY from tool results, never from `final_text` - a bogus number
    in the model's free text must pass through verbatim in its own place but must not replace or
    corrupt the tool-derived numeric section."""
    tool_calls = [tax_call(result=TAX_RESULT)]
    final_text = "Apytiksliai tai gali būti apie 999999 eur."

    text = build_response(tool_calls, final_text)

    assert final_text in text
    assert "1 234,56" in text  # the tool's real number, unaffected by the bogus one above
    assert "102,88" in text
    assert "987,65" in text


def test_negative_free_cash_monthly_renders_as_sane_negative_money_string():
    """Per `GoalsEngine`'s NO_FREE_CASH verdict, `free_cash_monthly` can be negative - must
    render as a normal signed amount, not raise or produce garbled output."""
    result = {
        "tier": "FREE",
        "core": {
            "monthly_contribution": 0.0,
            "free_cash_monthly": -50.5,
            "share_of_free_cash_pct": 0.0,
            "fits": False,
            "general_tip": "Pagal nurodytas pajamas ir išlaidas laisvų pinigų nelieka.",
            "assumptions": [],
            "disclaimer": "Šis skaičiavimas yra informacinio pobūdžio ir nėra finansinė konsultacija.",
        },
        "locked_features": [],
        "income_origin": "USER",
    }

    text = build_response([savings_call(result=result)], None)

    assert "-50,50 €" in text
    assert "0,00 €" in text


def test_assumptions_section_handles_unmapped_field_and_origin_gracefully():
    """Defensive design: an unexpected `field`/`origin` string (not one of the 3/4 known values)
    must degrade to the raw string, not crash and not produce a blank/ugly fallback."""
    assumptions = [
        {
            "field": "some_future_field",
            "value": 12.3,
            "origin": "SOME_FUTURE_ORIGIN",
            "source": None,
            "source_date": None,
            "is_illustrative": None,
        }
    ]
    result = {
        "tier": "FREE",
        "core": {
            "monthly_contribution": 10.0,
            "free_cash_monthly": 20.0,
            "share_of_free_cash_pct": 5.0,
            "fits": True,
            "general_tip": "tip",
            "assumptions": assumptions,
            "disclaimer": "disc",
        },
        "locked_features": [],
        "income_origin": "USER",
    }

    text = build_response([savings_call(result=result)], None)

    assert "some_future_field" in text
    assert "SOME_FUTURE_ORIGIN" in text


def test_cta_section_handles_unmapped_locked_feature_gracefully_no_crash_no_raw_dump():
    """An unrecognized `locked_features` entry is still a valid `pick_cta_feature` return value
    (no mapping to validate against there), but `LOCKED_FEATURE_CTA.get(...)` has no sentence for
    it - the CTA section must be silently omitted, not show a raw/ugly fallback string."""
    result = {
        "tier": "FREE",
        "core": {
            "monthly_contribution": 10.0,
            "free_cash_monthly": 20.0,
            "share_of_free_cash_pct": 5.0,
            "fits": True,
            "general_tip": "tip",
            "assumptions": [],
            "disclaimer": "disc",
        },
        "locked_features": ["SOME_FUTURE_LOCKED_FEATURE"],
        "income_origin": "USER",
    }

    assert pick_cta_feature(["SOME_FUTURE_LOCKED_FEATURE"]) == "SOME_FUTURE_LOCKED_FEATURE"

    text = build_response([savings_call(result=result)], None)

    assert "SOME_FUTURE_LOCKED_FEATURE" not in text
    assert "prisijung" not in text.lower()


def test_origin_labels_exactly_match_the_established_vocabulary_in_taupymo_tikslai_html():
    """AC-B4-1: `ORIGIN_LABELS` must be a verbatim port of the JS `ORIGIN_LABELS` object in
    taupymo-tikslai.html - reads the real template source so this fails if either side drifts."""
    import pathlib
    import re

    from app.response_builder import ORIGIN_LABELS

    html_path = (
        pathlib.Path(__file__).resolve().parents[2]
        / "src/main/resources/templates/taupymo-tikslai.html"
    )
    html = html_path.read_text(encoding="utf-8")
    match = re.search(r'var ORIGIN_LABELS = \{([^}]*)\};', html)
    assert match is not None, "ORIGIN_LABELS not found in taupymo-tikslai.html - template moved?"
    pairs = re.findall(r'(\w+):\s*"([^"]*)"', match.group(1))
    expected = dict(pairs)

    assert expected, "regex extracted nothing - check the template's ORIGIN_LABELS literal syntax"
    assert ORIGIN_LABELS == expected


def test_locked_feature_cta_sentences_match_html_source_byte_for_byte():
    """Companion to the exact ORIGIN_LABELS check above: the module docstring/comment claims
    `LOCKED_FEATURE_CTA` is "verbatim" from taupymo-tikslai.html - this asserts that literally,
    including dash style (QA originally caught a hyphen-vs-en-dash drift in two of the three
    sentences here, now fixed; this test is the byte-exact regression guard against it
    recurring, not a normalized/approximate comparison).
    """
    import pathlib
    import re

    from app.response_builder import LOCKED_FEATURE_CTA

    html_path = (
        pathlib.Path(__file__).resolve().parents[2]
        / "src/main/resources/templates/taupymo-tikslai.html"
    )
    html = html_path.read_text(encoding="utf-8")
    match = re.search(r"var LOCKED_FEATURE_CTA = \{(.*?)\};", html, re.DOTALL)
    assert match is not None, "LOCKED_FEATURE_CTA not found in taupymo-tikslai.html - template moved?"
    block = match.group(1)
    # Each entry is `KEY: "chunk" + "chunk" + ...` possibly spanning multiple string literals.
    entries = re.findall(r'(\w+):\s*((?:"[^"]*"\s*\+?\s*)+)', block)
    html_sentences = {}
    for key, chunks in entries:
        pieces = re.findall(r'"([^"]*)"', chunks)
        html_sentences[key] = "".join(pieces)

    assert set(html_sentences) == set(LOCKED_FEATURE_CTA)
    for key, html_sentence in html_sentences.items():
        assert LOCKED_FEATURE_CTA[key] == html_sentence, (
            f"{key} is not byte-for-byte identical between response_builder.py and "
            "taupymo-tikslai.html"
        )


def test_general_tip_informal_rewrites_cover_every_literal_string_savings_plan_service_emits():
    """Review round 1 SHOULD #2: `GENERAL_TIP_INFORMAL_LT`'s keys are meant to be the 4 exact,
    literal strings `SavingsPlanService.generalTip(Verdict)` (Java) can return - if Java's wording
    ever changes, this map's keys silently stop matching and `_build_goal_section` would fall back
    to rendering Java's (now formal-register, unmapped) text unchanged. Extracts the 4 literal
    string chunks straight from the Java source and asserts every one is a key in the Python map,
    same cross-source regression pattern as the CTA-vs-HTML test above."""
    import pathlib
    import re

    from app.response_builder import GENERAL_TIP_INFORMAL_LT

    java_path = (
        pathlib.Path(__file__).resolve().parents[2]
        / "src/main/java/eu/atsidek/goals/plan/SavingsPlanService.java"
    )
    java = java_path.read_text(encoding="utf-8")
    match = re.search(r"private static String generalTip\(Verdict verdict\) \{(.*?)\n    \}", java, re.DOTALL)
    assert match is not None, "generalTip(Verdict) not found in SavingsPlanService.java - moved/renamed?"
    body = match.group(1)
    cases = re.findall(r"case \w+ ->\s*((?:\"[^\"]*\"\s*\+?\s*)+);", body)
    assert len(cases) == 4, f"expected exactly 4 Verdict cases, found {len(cases)} - Verdict enum changed?"
    for case in cases:
        java_sentence = "".join(re.findall(r'"([^"]*)"', case))
        assert java_sentence in GENERAL_TIP_INFORMAL_LT, (
            f"SavingsPlanService.generalTip() now returns a string with no informal-register "
            f"rewrite in GENERAL_TIP_INFORMAL_LT: {java_sentence!r}"
        )
        # The rewrite must still be informal ("tu"/"tavo"), not an accidental no-op copy of the
        # formal original (which would silently reintroduce the register clash this map exists
        # to fix) - only assert this for the two sentences that actually use a 2nd-person form.
        if "jūsų" in java_sentence or "apsvarstykite" in java_sentence:
            assert GENERAL_TIP_INFORMAL_LT[java_sentence] != java_sentence
            assert "jūsų" not in GENERAL_TIP_INFORMAL_LT[java_sentence]
            assert "apsvarstykite" not in GENERAL_TIP_INFORMAL_LT[java_sentence]
