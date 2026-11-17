"""calculate_savings_plan tool (B2, AC-B2-1..4). Implemented autonomously (see Owner decision 13,
PLAN-taupymas-agentas.B.plan.md) — the technical design rationale (why net_monthly_income is the
trickiest part, the three resolution paths) is still written up in
docs/specs/taupymo-tikslai/B2-pairing-notes.md, kept for that reason even though the pairing
framing itself no longer applies.

  * `CalculateSavingsPlanInput` (AC-B2-1) — schema shape, tax_calc_id/net_monthly_income
    mutually exclusive.
  * `resolve_income` (AC-B2-2/3) — the one place that decides where net_monthly_income comes
    from: a prior calculate_iv_taxes result (tax_calc_id), a user-stated figure, or `None` when
    neither is given (owner decision, 2026-11-17, after the first real staging walkthrough:
    income is never guessed from statistics, but the monthly contribution/reach-date CAN still be
    computed without it — only the free-cash fit assessment needs income, see
    `SavingsPlanService.buildCore` on the Java side).
  * `make_calculate_savings_plan_tool` (AC-B2-4) — thin handler: validate -> resolve_income ->
    call `Calculator.savings_plan()` (same context-token-relay convention as B1's tax tool) ->
    curate what the model sees.
"""
import re
from dataclasses import dataclass
from decimal import Decimal
from typing import Callable, Literal, Optional

from pydantic import BaseModel, ConfigDict, model_validator

from .calculator import Calculator
from .errors import HallucinatedIncomeError
from .request_context import RequestContext

TOOL_NAME = "calculate_savings_plan"

# Owner decision, 2026-11-17 (real staging bug: for "Noriu sutaupyti 8000 eurų per 4 metus
# kelionei." the model computed 8000/4=2000 and passed it off as net_monthly_income - a number it
# invented from the GOAL itself). Written AS AN INSTRUCTION TO THE MODEL (same convention as
# MISSING_INCOME_MESSAGE used to be) - this is exactly what the model sees in the is_error
# tool_result block, and must tell it how to recover: retry without the fabricated figure.
HALLUCINATED_INCOME_MESSAGE = (
    "net_monthly_income atrodo išvestas iš tikslo sumos/termino (amount/years ar jo ×12), ne iš "
    "vartotojo pasakytos pajamų sumos - tai NIEKADA leidžiama, net kaip apytikslė prielaida. "
    "Pakartok kvietimą BE net_monthly_income ir BE tax_calc_id laukų (jei vartotojas tikrai "
    "nenurodė savo pajamų), arba su TIKRU jo paties pasakytu pajamų skaičiumi, jei toks yra."
)

_DIGIT_RUN = re.compile(r"\d+")


def _number_appears_in_text(value: Decimal, text: str) -> bool:
    """Heuristic (not exact-NLP) containment check: True if VALUE's integer part appears as a
    standalone digit run anywhere in TEXT. Good enough for a GUARD against an obviously-derived
    number; not meant to parse amounts/formatting precisely."""
    target = str(int(value))
    return target in _DIGIT_RUN.findall(text)


def _income_looks_derived_from_goal(goal: "GoalInput", candidate: Decimal) -> bool:
    """Owner decision, 2026-11-17: matches the exact hallucination pattern from the real bug - a
    model-supplied income figure that coincidentally equals the goal amount divided by its term,
    or that quotient times 12 (monthly-income -> annual-revenue style confusion). A term is only
    unambiguously known here for YEARS (the `years` field directly) and CHILD_AGE (target minus
    current age) - DATE's term would need "today" (a time-dependent computation this heuristic
    deliberately doesn't attempt), and WHEN mode's `monthly_contribution` IS the user's own input,
    not a figure to derive income from."""
    term_years: Optional[int] = None
    if goal.mode == "YEARS" and goal.years:
        term_years = goal.years
    elif goal.mode == "CHILD_AGE" and goal.child_current_age is not None and goal.child_target_age is not None:
        term_years = goal.child_target_age - goal.child_current_age
    if not term_years or term_years <= 0:
        return False
    quotient = goal.amount / term_years
    tolerance = Decimal("0.01")
    return abs(candidate - quotient) <= tolerance or abs(candidate - quotient * 12) <= tolerance


def _nullable(t: str, description: str) -> dict:
    return {"type": [t, "null"], "description": description}


# Hand-written, NOT auto-generated from the Pydantic models below (B3 task notes): Pydantic's
# model_json_schema() on CalculateSavingsPlanInput produces $defs/$ref for the nested
# Goal/Household/Assumptions models, which Claude's tool-calling `input_schema` does not reliably
# accept - so each nested shape gets its own flat, hand-written schema dict instead, following
# extractor.py's SCHEMA/TOOL_DEF precedent. Field names/enums are cross-checked against the
# corresponding Pydantic models by test_savings_tool.py.
GOAL_SCHEMA = {
    "type": "object",
    "properties": {
        "type": {
            "type": "string",
            "enum": ["CHILD", "CAR", "HOME_DOWN_PAYMENT", "OTHER"],
            "description": "Taupymo tikslo tipas.",
        },
        "mode": {
            "type": "string",
            "enum": ["YEARS", "DATE", "CHILD_AGE", "WHEN"],
            "description": (
                "Kaip apibrėžtas terminas: YEARS (metų skaičius), DATE (konkreti data), "
                "CHILD_AGE (vaiko amžius), WHEN (klausiama, kada bus sukaupta)."
            ),
        },
        "amount": {"type": "number", "description": "Tikslo suma eurais. Privaloma."},
        "years": _nullable("integer", "Metų skaičius iki tikslo, kai mode=YEARS."),
        "target_year": _nullable("integer", "Tikslo metai, kai mode=DATE."),
        "target_month": _nullable("integer", "Tikslo mėnuo (1-12), kai mode=DATE."),
        "child_current_age": _nullable("integer", "Vaiko amžius dabar, kai mode=CHILD_AGE."),
        "child_target_age": _nullable("integer", "Vaiko amžius tikslo momentu, kai mode=CHILD_AGE."),
        "monthly_contribution": _nullable(
            "number", "Mėnesinė įmoka eurais, kai mode=WHEN (klausiama, kada bus sukaupta)."),
    },
    "required": ["type", "mode", "amount"],
}

HOUSEHOLD_SCHEMA = {
    # Owner-reported eval crash (2026-11-17, live run under `--concurrency 8`): the model sent
    # the STRING "null" for this (optional, object-typed) field - pydantic then rejected it
    # ("Input should be a valid dictionary or instance of HouseholdInput"), crashing the whole
    # run. Root cause: unlike every scalar optional field above (which uses `_nullable()`), this
    # object's own JSON schema `type` didn't list "null" as allowed - a model that wants to
    # explicitly state "no household info" (rather than omitting the key) had no legal `null`
    # token to emit for an object-typed field, so it fell back to the string "null" instead.
    "type": ["object", "null"],
    "properties": {
        "adults": _nullable("integer", "Suaugusių namų ūkio narių skaičius."),
        "children": _nullable("integer", "Vaikų namų ūkio narių skaičius."),
    },
    "required": [],
}

ASSUMPTIONS_SCHEMA = {
    # Same fix as HOUSEHOLD_SCHEMA above, same reasoning - this object-typed optional field had
    # the identical "type" gap.
    "type": ["object", "null"],
    "properties": {
        "annual_return_pct": _nullable("number", "Metinė investicijų grąža procentais (pvz. 3.5)."),
        "annual_inflation_pct": _nullable("number", "Metinė infliacija procentais (pvz. 2.5)."),
    },
    "required": [],
}

SCHEMA = {
    "type": "object",
    "properties": {
        "goal": GOAL_SCHEMA,
        "monthly_expenses": _nullable("number", "Mėnesinės namų ūkio išlaidos eurais. null, jei nežinoma."),
        "household": HOUSEHOLD_SCHEMA,
        "assumptions": ASSUMPTIONS_SCHEMA,
        "tax_calc_id": {
            "type": ["string", "null"],
            "description": (
                "Ankstesnio calculate_iv_taxes kvietimo grąžintas tax_calc_id, iš kurio paimamos "
                "grynosios mėnesio pajamos. Naudok ARBA šitą, ARBA net_monthly_income - NIEKADA "
                "abu vienu metu. Jei nei vieno neturi, NESPĖLIOK pajamų (niekada neišvesk jos iš "
                "tikslo sumos, termino ar kito skaičiaus žinutėje) - tiesiog praleisk abu šiuos "
                "laukus, vis tiek paskaičiuosi mėnesio įmoką/datą, tik be laisvų pinigų įvertinimo."
            ),
        },
        "net_monthly_income": {
            "type": ["number", "null"],
            "description": (
                "Vartotojo paties nurodytos grynosios mėnesio pajamos eurais, kai nėra "
                "tax_calc_id. Naudok ARBA šitą, ARBA tax_calc_id - NIEKADA abu vienu metu."
            ),
        },
    },
    "required": ["goal"],
}

TOOL_DEF = {
    "name": TOOL_NAME,
    "description": (
        "Paskaičiuoja taupymo planą tikslui: mėnesio įmoką (arba datą, kai goal.mode=WHEN). "
        "Pajamas (tax_calc_id arba net_monthly_income) nustato TIK iš ankstesnio "
        "calculate_iv_taxes kvietimo arba vartotojo AIŠKIAI pasakyto skaičiaus - niekada "
        "nespėlioja/neišveda jų iš tikslo sumos, termino ar kito žinutės skaičiaus. Kai nei "
        "vieno neturi, abu laukus praleisk - įmoka/data vis tiek paskaičiuojama, tik atsakyme "
        "nebus laisvų pinigų/telpa-netelpa įvertinimo."
    ),
    "input_schema": SCHEMA,
}


class GoalInput(BaseModel):
    """Mirrors `SavingsPlanRequest.GoalRequest` (Java) field-for-field.

    `type`/`mode` are `Literal`s mirroring Java's `GoalType`/`GoalMode` enums exactly (review
    round 1 SHOULD #3) - an invalid value fails fast here with a local Pydantic `ValidationError`
    instead of an opaque Java 400.
    """

    model_config = ConfigDict(extra="forbid")

    type: Literal["CHILD", "CAR", "HOME_DOWN_PAYMENT", "OTHER"]
    mode: Literal["YEARS", "DATE", "CHILD_AGE", "WHEN"]
    amount: Decimal
    years: Optional[int] = None
    target_year: Optional[int] = None
    target_month: Optional[int] = None
    child_current_age: Optional[int] = None
    child_target_age: Optional[int] = None
    monthly_contribution: Optional[Decimal] = None


class HouseholdInput(BaseModel):
    """Mirrors `SavingsPlanRequest.HouseholdRequest`."""

    model_config = ConfigDict(extra="forbid")

    adults: Optional[int] = None
    children: Optional[int] = None


class AssumptionsInput(BaseModel):
    """Mirrors `SavingsPlanRequest.AssumptionsRequest`."""

    model_config = ConfigDict(extra="forbid")

    annual_return_pct: Optional[Decimal] = None
    annual_inflation_pct: Optional[Decimal] = None


class CalculateSavingsPlanInput(BaseModel):
    """AC-B2-1: goal fields + expenses + household + assumptions, plus exactly one of
    `tax_calc_id` (B1 result lookup) or `net_monthly_income` (user-stated figure) — never both.
    Neither present is allowed at the SCHEMA level (that's AC-B2-3's "need income" tool error,
    a runtime/handler concern, not a static shape concern — see the pairing notes doc).
    """

    model_config = ConfigDict(extra="forbid")

    goal: GoalInput
    monthly_expenses: Optional[Decimal] = None
    household: Optional[HouseholdInput] = None
    assumptions: Optional[AssumptionsInput] = None
    tax_calc_id: Optional[str] = None
    net_monthly_income: Optional[Decimal] = None

    @model_validator(mode="after")
    def _tax_calc_id_and_net_monthly_income_are_mutually_exclusive(self) -> "CalculateSavingsPlanInput":
        if self.tax_calc_id is not None and self.net_monthly_income is not None:
            raise ValueError(
                "tax_calc_id ir net_monthly_income - tik vienas iš dviejų, ne abu vienu metu")
        return self


@dataclass(frozen=True)
class ResolvedIncome:
    """Result of `resolve_income` - a concrete income figure to call Java with, plus where it
    came from (`origin`, surfaced to the model/B4's narration so it can say e.g. "pagal tavo
    anksčiau apskaičiuotus mokesčius" vs. "pagal tavo nurodytas pajamas"). Both `net_monthly_income`
    and `origin` are `None` when the caller gave neither `tax_calc_id` nor `net_monthly_income`
    (owner decision, 2026-11-17) - Java still computes the monthly contribution/reach date without
    income, it just skips the free-cash fit assessment (see `SavingsPlanService.buildCore`)."""

    net_monthly_income: Optional[Decimal]
    monthly_tax_setaside: Optional[Decimal]
    origin: Optional[Literal["TAX_CALC", "USER"]]


def resolve_income(parsed: CalculateSavingsPlanInput, request_context: RequestContext) -> ResolvedIncome:
    """AC-B2-2/3: the one place that decides where `net_monthly_income` comes from, so the
    handler itself never has to branch on it.

    - `tax_calc_id` given -> look up B1's stored calculate_iv_taxes result. If the id is unknown,
      `request_context.get_result` raises `UnknownResultError` (a `ToolError`) - let it propagate
      unchanged, that is already the correct "model-recoverable" shape (B3).
    - If the stored entry exists but is missing `better.netMonthlyIncome`/`better.monthlyReserve`,
      that is a JAVA-CONTRACT VIOLATION (B1 is supposed to always store that shape), not a tool
      error for the model to recover from - a bare `KeyError` is left to propagate uncaught here,
      deliberately NOT wrapped into a ToolError.
    - `net_monthly_income` given directly (no `tax_calc_id`, enforced mutually exclusive by the
      schema already) -> use it as-is, origin USER, no tax set-aside (Java treats None as 0).
    - Neither given -> `ResolvedIncome(None, None, None)` (owner decision, 2026-11-17: the FIRST
      real staging walkthrough found that the previous behavior - a hard `MissingIncomeError`
      stopping the whole calculation - was the wrong product call for e.g. "I want to save €8000
      in 4 years for a trip" with no income mentioned at all. Income is STILL never guessed from
      statistics or derived from the goal's own numbers (system_prompt.py), but the contribution/
      reach-date math doesn't need it, so there's no reason to refuse to answer).
    """
    if parsed.tax_calc_id is not None:
        stored = request_context.get_result(parsed.tax_calc_id)
        better = stored["better"]
        return ResolvedIncome(
            net_monthly_income=better["netMonthlyIncome"],
            monthly_tax_setaside=better["monthlyReserve"],
            origin="TAX_CALC",
        )
    if parsed.net_monthly_income is not None:
        return ResolvedIncome(
            net_monthly_income=parsed.net_monthly_income,
            monthly_tax_setaside=None,
            origin="USER",
        )
    return ResolvedIncome(net_monthly_income=None, monthly_tax_setaside=None, origin=None)


def make_calculate_savings_plan_tool(api: Calculator) -> Callable[[dict, RequestContext, Optional[str]], dict]:
    """Binds `api` once per app instance (same shape as B1's `make_calculate_iv_taxes_tool`).

    Body is deliberately thin: validate -> resolve_income -> build the Java request body ->
    call `api.savings_plan()` with the SAME context-token-relay convention as B1's tax tool ->
    curate what the model sees. All the actual decisions (where income comes from, what counts
    as an error) live in `resolve_income`/`CalculateSavingsPlanInput`, not here.
    """

    def handle(tool_input: dict, request_context: RequestContext, context_token: Optional[str] = None) -> dict:
        parsed = CalculateSavingsPlanInput.model_validate(tool_input)
        income = resolve_income(parsed, request_context)

        # Owner decision, 2026-11-17: only the USER-stated-figure path can possibly be a
        # hallucination - a TAX_CALC-origin income came from a prior, already-validated
        # calculate_iv_taxes result, never from the model inventing a number.
        if (income.origin == "USER" and income.net_monthly_income is not None
                and _income_looks_derived_from_goal(parsed.goal, income.net_monthly_income)
                and not _number_appears_in_text(income.net_monthly_income, request_context.message_text)):
            raise HallucinatedIncomeError(HALLUCINATED_INCOME_MESSAGE)

        goal = parsed.goal.model_dump()
        household = parsed.household.model_dump() if parsed.household is not None else None
        assumptions = parsed.assumptions.model_dump() if parsed.assumptions is not None else None

        java_result = api.savings_plan(
            goal,
            income.net_monthly_income,
            income.monthly_tax_setaside,
            parsed.monthly_expenses,
            household,
            assumptions,
            context_token,
        )

        out = {
            "tier": java_result["tier"],
            "core": java_result["core"],
            "locked_features": java_result["locked_features"],
            "income_origin": income.origin,
        }
        # SavingsPlanResponse's @JsonInclude(NON_NULL): the key is simply ABSENT for an
        # anonymous caller, not null - preserve that exactly, never invent a premium block.
        if "premium" in java_result:
            out["premium"] = java_result["premium"]
        return out

    return handle
