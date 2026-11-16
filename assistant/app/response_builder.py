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
        "Tavo pajamos svyruoja – jei prisijungsi, pamatysi, kiek atsidėti stipresniais ir "
        "silpnesniais mėnesiais."
    ),
    "SAVINGS_SCENARIOS": (
        "Jei prisijungsi, galėsi greta palyginti keletą taupymo scenarijų – pvz. skirtingus "
        "terminus ar sumas."
    ),
}
PREFERRED_CTA_FEATURE = "SAVINGS_IV_LINK"

# Owner instruction (2026-11-16, after B6 eval #11, item 3): "Tavo pajamos svyruoja" presumes
# irregular/IV income - only true when the resolved net_monthly_income actually came from a prior
# calculate_iv_taxes call (`income_origin == "TAX_CALC"`, see savings_tool.ResolvedIncome). When it
# came from the user's own stated figure instead, the premise doesn't hold - use this neutral
# variant (same feature, same CTA, just without the IV-specific opening clause).
LOCKED_FEATURE_CTA_NEUTRAL_IV = (
    "Jei prisijungsi, pamatysi, kiek atsidėti stipresniais ir silpnesniais mėnesiais."
)

# Owner instruction (2026-11-16, after B6 eval #11, item 3): "jei prisijungsi" makes no sense to
# someone who IS already logged in (FREE tier, beta off - during beta every logged-in user gets
# all 3 features, so `locked_features` is empty and no CTA renders at all, same as today). These
# are the logged-in-FREE upgrade equivalents of `LOCKED_FEATURE_CTA` - same structure/feature
# coverage, own wording not specified by the owner, since none exists yet to port from.
LOGGED_IN_FREE_CTA = {
    "SAVINGS_MULTI_GOAL": (
        "Turi daugiau nei vieną taupymo tikslą? Jei atnaujinsi iki Premium, galėsi sekti keletą "
        "tikslų vienu metu, ne tik vieną."
    ),
    "SAVINGS_IV_LINK": (
        "Tavo pajamos svyruoja – jei atnaujinsi iki Premium, pamatysi, kiek atsidėti stipresniais "
        "ir silpnesniais mėnesiais."
    ),
    "SAVINGS_SCENARIOS": (
        "Jei atnaujinsi iki Premium, galėsi greta palyginti keletą taupymo scenarijų – pvz. "
        "skirtingus terminus ar sumas."
    ),
}
LOGGED_IN_FREE_CTA_NEUTRAL_IV = (
    "Jei atnaujinsi iki Premium, pamatysi, kiek atsidėti stipresniais ir silpnesniais mėnesiais."
)

# Review round 1 SHOULD #2: `core["general_tip"]` (SavingsPlanService.generalTip(), Java) is
# written in the formal "jūs" register - it has never actually been RENDERED to a user before
# (taupymo-tikslai.html receives the field but doesn't display it), so this is the first time its
# register is visible, landing right next to the informal "tu" CTA sentences above in the same
# chat reply. The rest of this feature's established voice (the CTA text itself, confirmed by
# taupymo-tikslai.html's own comment about deliberately adapting FROM formal TO informal for this
# exact reason) is informal, so this map rewrites Java's 4 fixed strings into that register rather
# than the other way around - changing Java's text is out of this Python-only phase's scope, and a
# second, chat-specific formal CTA variant would just duplicate vocabulary. A test cross-checks
# these 4 keys against SavingsPlanService.java's own literal strings, same pattern as the
# LOCKED_FEATURE_CTA-vs-HTML regression test below.
GENERAL_TIP_INFORMAL_LT = {
    "Laisvų pinigų nėra arba jie neigiami – pirmiausia peržiūrėkite išlaidas, "
    "kad atsirastų, iš ko taupyti.": (
        "Laisvų pinigų nėra arba jie neigiami – pirmiausia peržiūrėk išlaidas, "
        "kad atsirastų, iš ko taupyti."
    ),
    "Šis tikslas atrodo pasiekiamas – įmoka sudaro nedidelę dalį jūsų laisvų pinigų.": (
        "Šis tikslas atrodo pasiekiamas – įmoka sudaro nedidelę dalį tavo laisvų pinigų."
    ),
    "Šis tikslas pasiekiamas, bet pareikalaus nemažos dalies laisvų pinigų – "
    "apsvarstykite ilgesnį terminą arba mažesnę sumą.": (
        "Šis tikslas pasiekiamas, bet pareikalaus nemažos dalies laisvų pinigų – "
        "apsvarstyk ilgesnį terminą arba mažesnę sumą."
    ),
    "Reikalinga įmoka viršija pusę jūsų laisvų pinigų – su šiuo terminu tikslas "
    "greičiausiai nerealus, apsvarstykite ilgesnį terminą arba mažesnę sumą.": (
        "Reikalinga įmoka viršija pusę tavo laisvų pinigų – su šiuo terminu tikslas "
        "greičiausiai nerealus, apsvarstyk ilgesnį terminą arba mažesnę sumą."
    ),
}


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


def _last_successful_call(tool_calls: list[dict], name: str) -> Optional[dict]:
    for call in reversed(tool_calls):
        if call["name"] == name and call["is_error"] is False:
            return call
    return None


def _last_successful_result(tool_calls: list[dict], name: str) -> Optional[dict]:
    call = _last_successful_call(tool_calls, name)
    return call["result"] if call is not None else None


_MONTH_NAMES_LT = {
    1: "sausio", 2: "vasario", 3: "kovo", 4: "balandžio", 5: "gegužės", 6: "birželio",
    7: "liepos", 8: "rugpjūčio", 9: "rugsėjo", 10: "spalio", 11: "lapkričio", 12: "gruodžio",
}


def _format_date_lt(iso_date: str) -> str:
    """`core["reach_date"]` is Java's `LocalDate` serialized as plain ISO (`YYYY-MM-DD`, Spring
    Boot's default JSR310 format, WRITE_DATES_AS_TIMESTAMPS disabled) - never parsed/re-derived
    here, just reformatted for display."""
    year, month, _day = iso_date.split("-")
    return f"{int(year)} m. {_MONTH_NAMES_LT[int(month)]} mėn."


def _build_taxes_section(tax_result: dict) -> str:
    method_label = EXPENSE_METHOD_LABELS_LT.get(tax_result["method"], tax_result["method"])
    return (
        f"Pagal {method_label} metodą, metinė mokesčių suma yra {_eur(tax_result['total_tax_per_year'])}, "
        f"mėnesiui verta atsidėti {_eur(tax_result['monthly_tax_setaside'])}, "
        f"o grynosios mėnesio pajamos - {_eur(tax_result['net_monthly_income'])}."
    )


def _build_free_cash_section(core: dict) -> str:
    return f"Laisvų pinigų per mėnesį lieka {_eur(core['free_cash_monthly'])}."


def _build_goal_section(core: dict) -> str:
    """Owner instruction (2026-11-13, second B6 live-eval round): when there is no free cash at
    all (`free_cash_monthly <= 0`, Java's `GoalsEngine.verdict()` NO_FREE_CASH branch), showing
    "0,0% laisvų pinigų" is misleading - it reads as "the contribution is a comfortable 0% share",
    not "there is nothing to share at all". Say plainly that there is no free cash and the
    contribution does not fit, instead of rendering a percentage of zero."""
    if Decimal(str(core["free_cash_monthly"])) <= 0:
        sentence = (
            f"Laisvų pinigų nėra - siūloma {_eur(core['monthly_contribution'])} per mėnesį įmoka "
            "į tikslą netelpa."
        )
    else:
        sentence = (
            f"Tikslui siūloma atsidėti {_eur(core['monthly_contribution'])} per mėnesį "
            f"({_pct(core['share_of_free_cash_pct'])} laisvų pinigų)."
        )
    general_tip = GENERAL_TIP_INFORMAL_LT.get(core["general_tip"], core["general_tip"])
    return f"{sentence} {general_tip}"


def _build_when_reach_section(core: dict) -> Optional[str]:
    """Owner instruction (2026-11-16, after B6 eval #11, item 2): WHEN mode's goal-reach date
    (or "unreachable within 50 years") is a core (free) field (see `Core.reachable`/`reachDate`,
    Java) - must actually be shown, not silently dropped. Only called when the savings tool call's
    own INPUT had `goal.mode == "WHEN"` - for every other mode the term is an input, not Java's
    output, and `reachable`/`reach_date` carry no information worth rendering (see Core.java's own
    javadoc: always `reachable=true`/`reach_date=null` there)."""
    if not core.get("reachable", True):
        return "Su nurodyta įmoka tikslas nepasiekiamas per 50 metų."
    reach_date = core.get("reach_date")
    if reach_date is None:
        return None
    return f"Su nurodyta įmoka tikslą pasieksite {_format_date_lt(reach_date)}."


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


def _build_cta_section(locked_features: list[str], is_logged_in: bool, income_origin: Optional[str]) -> Optional[str]:
    """Owner instruction (2026-11-16, after B6 eval #11, item 3): `is_logged_in` picks the
    "jei prisijungsi" vs "jei atnaujinsi iki Premium" wording - during beta, a logged-in user's
    `locked_features` is already empty (full access), so this never fires for them regardless of
    `is_logged_in`, matching "beta metu – jokio CTA" without any extra beta-flag plumbing here.
    `income_origin` swaps in the IV-neutral variant for SAVINGS_IV_LINK when the resolved income
    did NOT come from a prior calculate_iv_taxes call."""
    feature = pick_cta_feature(locked_features)
    if feature is None:
        return None
    is_iv_income = income_origin == "TAX_CALC"
    if is_logged_in:
        if feature == "SAVINGS_IV_LINK" and not is_iv_income:
            return LOGGED_IN_FREE_CTA_NEUTRAL_IV
        return LOGGED_IN_FREE_CTA.get(feature)
    if feature == "SAVINGS_IV_LINK" and not is_iv_income:
        return LOCKED_FEATURE_CTA_NEUTRAL_IV
    return LOCKED_FEATURE_CTA.get(feature)


def build_response(tool_calls: list[dict], final_text: Optional[str]) -> str:
    """AC-B4-1: assembles the deterministic reply section-by-section, in the fixed order
    taxes -> free cash -> goal -> assumptions-with-origin -> disclaimer -> CTA. Each section is
    present only if its backing tool result is present; numbers are the tool results verbatim,
    never re-derived or re-rounded here beyond display formatting.
    """
    tax_result = _last_successful_result(tool_calls, TAX_TOOL_NAME)
    savings_call = _last_successful_call(tool_calls, SAVINGS_TOOL_NAME)
    savings_result = savings_call["result"] if savings_call is not None else None

    sections: list[str] = []

    if tax_result is not None:
        sections.append(_build_taxes_section(tax_result))

    if savings_result is not None:
        core = savings_result["core"]
        sections.append(_build_free_cash_section(core))
        sections.append(_build_goal_section(core))
        goal_mode = ((savings_call.get("input") or {}).get("goal") or {}).get("mode")
        if goal_mode == "WHEN":
            when_section = _build_when_reach_section(core)
            if when_section is not None:
                sections.append(when_section)
        if core["assumptions"]:
            sections.append(_build_assumptions_section(core["assumptions"]))
        sections.append(core["disclaimer"])
        if savings_result["locked_features"]:
            is_logged_in = "premium" in savings_result
            cta = _build_cta_section(
                savings_result["locked_features"], is_logged_in, savings_result.get("income_origin"))
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
