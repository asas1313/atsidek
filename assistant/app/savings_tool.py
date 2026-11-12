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
from decimal import Decimal
from typing import Any, Callable, Optional

from pydantic import BaseModel, ConfigDict, model_validator

TOOL_NAME = "calculate_savings_plan"


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
