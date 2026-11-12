"""Builds the deterministic, user-facing chat reply from `AgentLoopResult.tool_calls` +
`AgentLoopResult.final_text` (B4, AC-B4-1/AC-B4-2).

The model's own free text (`final_text`) is, per `system_prompt.SYSTEM_PROMPT`'s own role
restriction, only ever an intro/explanation/clarifying question - it is NEVER trusted for numbers.
Every number in the sections this module builds comes straight from the Java-computed tool
results (`tax_tool.handle()`/`savings_tool.handle()`'s return shapes), via a template, not via the
model's text - that is the whole point of AC-B4-1.
"""
from decimal import Decimal
from typing import Any, Optional

TAX_TOOL_NAME = "calculate_iv_taxes"
SAVINGS_TOOL_NAME = "calculate_savings_plan"

# Java's `ExpenseMethod` enum (src/main/java/eu/atsidek/engine/ExpenseMethod.java) has exactly
# these two values - no other method string is ever produced.
EXPENSE_METHOD_LABELS_LT = {
    "ACTUAL": "faktinės išlaidos",
    "FLAT_30": "30 % išlaidų norma",
}

# The only 3 `field` values `SavingsPlanService` ever puts into `core.assumptions` today
# (src/main/java/eu/atsidek/goals/plan/SavingsPlanService.java lines 117-118, 229-231).
FIELD_LABELS_LT = {
    "monthly_expenses": "Mėnesio išlaidos",
    "annual_return_pct": "Metinė grąža",
    "annual_inflation_pct": "Metinė infliacija",
}

# Verbatim from src/main/resources/templates/taupymo-tikslai.html line 269 (`ORIGIN_LABELS`) -
# the established Lithuanian vocabulary for these exact `Origin` enum values; reused as-is, not
# reworded, for consistency with the rest of the product.
ORIGIN_LABELS = {"USER": "Jūsų", "AI": "AI", "STATISTICS": "Statistika", "ASSUMPTION": "Prielaida"}

# Verbatim from taupymo-tikslai.html lines 285-303 (`LOCKED_FEATURE_CTA`/`PREFERRED_CTA_FEATURE`) -
# same established wording/priority, ported to Python as-is (AC-B4-2).
LOCKED_FEATURE_CTA = {
    "SAVINGS_MULTI_GOAL": (
        "Turi daugiau nei vieną taupymo tikslą? Jei prisijungsi, galėsi sekti keletą tikslų vienu "
        "metu, ne tik vieną."
    ),
    "SAVINGS_IV_LINK": (
        "Tavo pajamos svyruoja - jei prisijungsi, pamatysi, kiek atsidėti stipresniais ir "
        "silpnesniais mėnesiais."
    ),
    "SAVINGS_SCENARIOS": (
        "Jei prisijungsi, galėsi greta palyginti keletą taupymo scenarijų - pvz. skirtingus "
        "terminus ar sumas."
    ),
}
PREFERRED_CTA_FEATURE = "SAVINGS_IV_LINK"


def pick_cta_feature(locked_features: list[str]) -> Optional[str]:
    """AC-B4-2: single, specific CTA - ported verbatim from `pickCtaFeature` in
    taupymo-tikslai.html (lines 677-680)."""
    if not locked_features:
        return None
    if PREFERRED_CTA_FEATURE in locked_features:
        return PREFERRED_CTA_FEATURE
    return locked_features[0]


def _eur(v: Any) -> str:
    """Mirrors main.py's `eur()` string style (',' -> ' ', '.' -> ',') for consistency, but is
    deliberately NOT imported from main.py - chat-text formatting here is a different concern
    (and a different convention, see `_pct` below) from main.py's Jinja2 HTML filters."""
    return f"{Decimal(str(v)):,.2f}".replace(",", " ").replace(".", ",") + " €"


def _pct(v: Any) -> str:
    """Unlike main.py's own `pct()` filter, this does NOT multiply by 100: the savings-plan
    values this module renders (`share_of_free_cash_pct`, assumption `*_pct` fields) are already
    percent-scaled by Java. Do not "fix" this into a double-multiply bug - it is intentional."""
    return f"{Decimal(str(v)):,.1f}".replace(",", " ").replace(".", ",") + "%"


def _last_successful_result(tool_calls: list[dict], name: str) -> Optional[dict]:
    for call in reversed(tool_calls):
        if call["name"] == name and call["is_error"] is False:
            return call["result"]
    return None


def _build_taxes_section(tax_result: dict) -> str:
    method_label = EXPENSE_METHOD_LABELS_LT.get(tax_result["method"], tax_result["method"])
    return (
        f"Pagal {method_label} metodą, metinis mokesčių suma yra {_eur(tax_result['total_tax_per_year'])}, "
        f"mėnesiui verta atsidėti {_eur(tax_result['monthly_tax_setaside'])}, "
        f"o grynosios mėnesio pajamos - {_eur(tax_result['net_monthly_income'])}."
    )


def _build_free_cash_section(core: dict) -> str:
    return f"Laisvų pinigų per mėnesį lieka {_eur(core['free_cash_monthly'])}."


def _build_goal_section(core: dict) -> str:
    sentence = (
        f"Tikslui siūloma atsidėti {_eur(core['monthly_contribution'])} per mėnesį "
        f"({_pct(core['share_of_free_cash_pct'])} laisvų pinigų)."
    )
    return f"{sentence} {core['general_tip']}"


def _build_assumptions_section(assumptions: list[dict]) -> str:
    lines = []
    for entry in assumptions:
        field = entry["field"]
        label = FIELD_LABELS_LT.get(field, field)
        if field == "monthly_expenses":
            value_str = _eur(entry["value"])
        else:
            value_str = _pct(entry["value"])
        origin = entry["origin"]
        origin_label = ORIGIN_LABELS.get(origin, origin)
        if origin == "STATISTICS" and entry.get("source"):
            origin_note = f"({origin_label}: {entry['source']})"
        else:
            origin_note = f"({origin_label})"
        lines.append(f"{label}: {value_str} {origin_note}")
    return "\n".join(lines)


def _build_cta_section(locked_features: list[str]) -> Optional[str]:
    feature = pick_cta_feature(locked_features)
    if feature is None:
        return None
    return LOCKED_FEATURE_CTA.get(feature)


def build_response(tool_calls: list[dict], final_text: Optional[str]) -> str:
    """AC-B4-1: assembles the deterministic reply section-by-section, in the fixed order
    taxes -> free cash -> goal -> assumptions-with-origin -> disclaimer -> CTA. Each section is
    present only if its backing tool result is present; numbers are the tool results verbatim,
    never re-derived or re-rounded here beyond display formatting.
    """
    tax_result = _last_successful_result(tool_calls, TAX_TOOL_NAME)
    savings_result = _last_successful_result(tool_calls, SAVINGS_TOOL_NAME)

    sections: list[str] = []

    if tax_result is not None:
        sections.append(_build_taxes_section(tax_result))

    if savings_result is not None:
        core = savings_result["core"]
        sections.append(_build_free_cash_section(core))
        sections.append(_build_goal_section(core))
        if core["assumptions"]:
            sections.append(_build_assumptions_section(core["assumptions"]))
        sections.append(core["disclaimer"])
        if savings_result["locked_features"]:
            cta = _build_cta_section(savings_result["locked_features"])
            if cta is not None:
                sections.append(cta)

    deterministic = "\n\n".join(sections)

    # Ordering choice (not specified by the plan, only the numeric section order is): the
    # model's own intro/explanation/clarifying-question text goes FIRST, since it is naturally a
    # lead-in to the deterministic numbers that follow, not a trailing remark after them.
    if final_text:
        if deterministic:
            return f"{final_text}\n\n{deterministic}"
        return final_text
    return deterministic


__all__ = [
    "FIELD_LABELS_LT",
    "LOCKED_FEATURE_CTA",
    "ORIGIN_LABELS",
    "PREFERRED_CTA_FEATURE",
    "build_response",
    "pick_cta_feature",
]
