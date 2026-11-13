"""calculate_savings_plan tool (B2, AC-B2-1..4). Implemented autonomously (see Owner decision 13,
PLAN-taupymas-agentas.B.plan.md) — the technical design rationale (why net_monthly_income is the
trickiest part, the three resolution paths) is still written up in
docs/specs/taupymo-tikslai/B2-pairing-notes.md, kept for that reason even though the pairing
framing itself no longer applies.

  * `CalculateSavingsPlanInput` (AC-B2-1) — schema shape, tax_calc_id/net_monthly_income
    mutually exclusive.
  * `resolve_income` (AC-B2-2/3) — the one place that decides where net_monthly_income comes
    from: a prior calculate_iv_taxes result (tax_calc_id), a user-stated figure, or
    MissingIncomeError when neither is given (income is never guessed from statistics).
  * `make_calculate_savings_plan_tool` (AC-B2-4) — thin handler: validate -> resolve_income ->
    call `Calculator.savings_plan()` (same context-token-relay convention as B1's tax tool) ->
    curate what the model sees.
"""
from dataclasses import dataclass
from decimal import Decimal
from typing import Callable, Literal, Optional

from pydantic import BaseModel, ConfigDict, model_validator

from .calculator import Calculator
from .errors import MissingIncomeError
from .request_context import RequestContext

TOOL_NAME = "calculate_savings_plan"

MISSING_INCOME_MESSAGE = (
    "Nėra grynųjų pajamų: paklausk vartotojo mėnesio grynųjų pajamų arba pirmiau iškviesk "
    "calculate_iv_taxes."
)


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
    "type": "object",
    "properties": {
        "adults": _nullable("integer", "Suaugusių namų ūkio narių skaičius."),
        "children": _nullable("integer", "Vaikų namų ūkio narių skaičius."),
    },
    "required": [],
}

ASSUMPTIONS_SCHEMA = {
    "type": "object",
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
                "abu vienu metu. Jei nei vieno neturi, NESPĖLIOK pajamų - paklausk vartotojo arba "
                "pirmiau iškviesk calculate_iv_taxes."
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
        "Paskaičiuoja taupymo planą tikslui pagal grynąsias mėnesio pajamas, išlaidas ir namų "
        "ūkio sudėtį. Pajamas nustato TIK per tax_calc_id arba net_monthly_income - niekada "
        "nespėlioja jų pats."
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
    anksčiau apskaičiuotus mokesčius" vs. "pagal tavo nurodytas pajamas")."""

    net_monthly_income: Decimal
    monthly_tax_setaside: Optional[Decimal]
    origin: Literal["TAX_CALC", "USER"]


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
    - Neither given -> `MissingIncomeError` (PLAN.md principle: never guess income from stats).
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
    raise MissingIncomeError(MISSING_INCOME_MESSAGE)


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
