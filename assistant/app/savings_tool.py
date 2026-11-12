"""calculate_savings_plan tool (B2, AC-B2-1..4) — pairing phase, see
docs/specs/taupymo-tikslai/B2-pairing-notes.md.

This module is split deliberately:
  * `CalculateSavingsPlanInput` (AC-B2-1) — DONE. Mirrors `SavingsPlanRequest`'s Java DTO shape
    (goal/household/assumptions) plus the two tool-only fields that don't exist on the Java side
    at all (`tax_calc_id`, `net_monthly_income` as direct alternatives to each other).
  * `make_calculate_savings_plan_tool(...)` — NOT implemented here on purpose. This is the
    pairing exercise: resolving a concrete `net_monthly_income` value (from a prior
    `calculate_iv_taxes` call via `tax_calc_id`, or from a user-stated figure) before calling
    Java's `/api/v1/savings/plan`, which always requires that field. See the pairing notes doc
    for exactly what decisions are yours to make here.
"""
from dataclasses import dataclass
from decimal import Decimal
from typing import Any, Callable, Literal, Optional

from pydantic import BaseModel, ConfigDict, model_validator

from .errors import MissingIncomeError
from .request_context import RequestContext

TOOL_NAME = "calculate_savings_plan"

MISSING_INCOME_MESSAGE = (
    "Nėra grynųjų pajamų: paklausk vartotojo mėnesio grynųjų pajamų arba pirmiau iškviesk "
    "calculate_iv_taxes."
)


class GoalInput(BaseModel):
    """Mirrors `SavingsPlanRequest.GoalRequest` (Java) field-for-field."""

    model_config = ConfigDict(extra="forbid")

    type: str
    mode: str
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


def make_calculate_savings_plan_tool(
    *args: Any, **kwargs: Any
) -> Callable[..., dict]:
    """B2 pairing exercise — not implemented here. See docs/specs/taupymo-tikslai/
    B2-pairing-notes.md for: the exact net_monthly_income resolution rules (AC-B2-2/3), how to
    forward the context token and user id (AC-B2-4, already fully supported by
    SavingsPlanApiController since B1 — no Java change needed), and what a new
    AtsidekApi.savings_plan() client method should look like (mirror of the existing
    AtsidekApi.compare()).
    """
    raise NotImplementedError(
        "B2: write this together — see docs/specs/taupymo-tikslai/B2-pairing-notes.md")
