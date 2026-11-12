"""calculate_iv_taxes tool (B1, AC-B1-1..4).

Wraps the EXISTING `AtsidekApi.compare()` (calculator.py) - no second HTTP client. The handler:
  * validates its input with a Pydantic model (Java-DTO-equivalent validation, B0 AC-B0-2 pattern),
  * calls `calculator.compare()` with the same five positional values AtsidekApi.compare() already
    takes today, plus the ADR-0003 context token forwarded unmodified as a sixth, optional value -
    this module never inspects/decodes the token itself (ADR-0003: Python is purely a relay),
  * stores the RAW Java response in the current turn's `RequestContext` and returns to "the model"
    only an opaque `tax_calc_id` plus a curated set of disclosed numbers (never the raw Java
    response/its internal field names - AC-B1-3).
"""
from decimal import Decimal
from typing import Callable, Optional

from pydantic import BaseModel, ConfigDict, Field

from .calculator import Calculator
from .request_context import RequestContext

TOOL_NAME = "calculate_iv_taxes"


def _nullable(t: str, description: str) -> dict:
    return {"type": [t, "null"], "description": description}


# Hand-written, not auto-generated from the Pydantic model below (B3 task notes): Pydantic's
# model_json_schema() would be fine here since this model has no nested sub-models, but every
# tool's input_schema in this codebase is hand-written (extractor.py's SCHEMA/TOOL_DEF precedent)
# for consistency with savings_tool.py's TOOL_DEF, which DOES need hand-writing (nested models
# produce $defs/$ref Claude's tool-calling API doesn't reliably accept). Field names/types/
# required-ness are cross-checked against CalculateIvTaxesInput by test_tax_tool.py.
SCHEMA = {
    "type": "object",
    "properties": {
        "revenue": {
            "type": "number",
            "description": "Numatomos metinės individualios veiklos (IV) pajamos eurais. Privaloma.",
        },
        "actual_expenses": _nullable(
            "number",
            "Faktinės metinės IV išlaidos eurais. null, jei taikoma 30 % išlaidų norma arba nežinoma.",
        ),
        "other_income": _nullable(
            "number", "Kitos metinės pajamos ne iš IV (darbo užmokestis, nuoma) eurais. null, jei nėra."),
        "pension_accumulation": {
            "type": "boolean",
            "description": "Ar vartotojas dalyvauja II pakopos pensijų kaupime. Numatytoji reikšmė false.",
        },
        "psd_min_months": {
            "type": "integer",
            "description": (
                "Kiek mėnesių per metus pats moka PSD (0-12); kitus mėnesius PSD moka darbdavys. "
                "Numatytoji reikšmė 12."
            ),
        },
    },
    "required": ["revenue"],
}

TOOL_DEF = {
    "name": TOOL_NAME,
    "description": (
        "Paskaičiuoja individualios veiklos (IV) mokesčius ir grynąsias mėnesio pajamas pagal "
        "numatomas metines pajamas. Mokesčius skaičiuoja TIK šis įrankis - niekada nespėliok."
    ),
    "input_schema": SCHEMA,
}


class CalculateIvTaxesInput(BaseModel):
    """Field names/types mirror `AtsidekApi.compare()`'s own parameter shape (revenue/expenses/
    other/pillar2/psd_months, AC-B1-1) - NOT Java's internal camelCase response field names.
    `extra="forbid"` matches the B0 pattern (`IvExtractionToolInput`) of rejecting unknown fields.
    """

    model_config = ConfigDict(extra="forbid")

    revenue: Decimal
    actual_expenses: Optional[Decimal] = None
    other_income: Optional[Decimal] = None
    pension_accumulation: bool = False
    # Review round 1 NIT #6: the schema description text above already promises "(0-12)" - this
    # constraint makes that true locally (fails fast with a Pydantic ValidationError) instead of
    # relying solely on Java's double guard (TaxApiController's 400 + TaxInput's defensive
    # clamp), which still exists and is why this was a NIT, not a correctness gap.
    psd_min_months: int = Field(default=12, ge=0, le=12)


def make_calculate_iv_taxes_tool(
    calculator: Calculator,
) -> Callable[[dict, RequestContext, Optional[str]], dict]:
    """Binds `calculator` once per app instance (same shape as B0's `registry.register(EXTRACT_TOOL_NAME,
    extractor.extract)` wiring in main.py) - the returned handler's only per-call arguments are the
    tool input, the current turn's `RequestContext`, and the context token to relay.
    """

    def handle(tool_input: dict, request_context: RequestContext, context_token: Optional[str] = None) -> dict:
        parsed = CalculateIvTaxesInput.model_validate(tool_input)
        java_result = calculator.compare(
            parsed.revenue,
            parsed.actual_expenses,
            parsed.other_income,
            parsed.pension_accumulation,
            parsed.psd_min_months,
            context_token,
        )
        tax_calc_id = request_context.store_result(java_result)
        better = java_result["better"]
        return {
            "tax_calc_id": tax_calc_id,
            "method": better["method"],
            "total_tax_per_year": better["total"],
            "monthly_tax_setaside": better["monthlyReserve"],
            # AC-B1-2: surfaced EXACTLY as Java computed it - no Python-side arithmetic.
            "net_monthly_income": better["netMonthlyIncome"],
        }

    return handle
