"""Atsidėk (Java) JSON API klientas. Visus mokesčius skaičiuoja jis, ne šis servisas ir ne modelis."""
import json
from decimal import Decimal
from typing import Optional, Protocol

import httpx


class CalculatorError(Exception):
    """Žinutė rodoma vartotojui.

    `status_code` (review round 1 SHOULD #2) nešioja Java pusės HTTP statusą, KAI jis žinomas -
    400, jei Java ATMETĖ įvestį (kliento duomenų problema), arba Java grąžinto klaidos kodo
    reikšmę kitiems ne-200 atsakymams. `None` reiškia "Java nepasiekiama" (tinklo klaida) arba
    "paslauga nesukonfigūruota" - šiais atvejais skambinantis kodas (žr. `main.py`
    `/internal/chat`) tai laiko upstream klaida (502), NE kliento klaida.
    """

    def __init__(self, message: str, status_code: Optional[int] = None):
        super().__init__(message)
        self.status_code = status_code


class Calculator(Protocol):
    def compare(self, revenue: Decimal, actual_expenses: Optional[Decimal], other_income: Optional[Decimal],
                pension_accumulation: bool, psd_min_months: int, context_token: Optional[str] = None) -> dict: ...


def _json_default(o):
    if isinstance(o, Decimal):
        return float(o)
    raise TypeError(type(o))


class AtsidekApi:
    def __init__(self, base_url: str, api_key: str, transport: Optional[httpx.BaseTransport] = None):
        self.api_key = api_key
        self._client = httpx.Client(base_url=base_url.rstrip("/"), timeout=10, transport=transport)

    def compare(self, revenue, actual_expenses, other_income, pension_accumulation, psd_min_months,
                context_token: Optional[str] = None) -> dict:
        if not self.api_key:
            raise CalculatorError("Skaičiuoklės paslauga nesukonfigūruota (trūksta ATSIDEK_API_KEY).")
        body = {
            "ivRevenue": revenue,
            "actualExpenses": actual_expenses if actual_expenses is not None else Decimal(0),
            "otherIncome": other_income if other_income is not None else Decimal(0),
            "pensionAccumulation": pension_accumulation,
            "psdMinMonths": psd_min_months,
        }
        # ADR-0003: žetonas (jei yra) tik perduodamas NEPAKEISTAS - šis servisas jo nei tikrina,
        # nei dekoduoja. Be žetono (šiandieninis /asistentas kelias) antraštė VISIŠKAI nesiunčiama,
        # ne tuščia reikšmė - nulinis elgsenos pokytis esamiems kvietimams.
        headers = {"X-API-Key": self.api_key, "Content-Type": "application/json"}
        if context_token:
            headers["X-Atsidek-Context-Token"] = context_token
        try:
            resp = self._client.post(
                "/api/v1/compare",
                content=json.dumps(body, default=_json_default),
                headers=headers,
            )
        except httpx.HTTPError as e:
            raise CalculatorError("Nepavyko pasiekti skaičiuoklės paslaugos.") from e
        if resp.status_code == 400:
            raise CalculatorError("Skaičiuoklė atmetė skaičius. Patikslink tekstą.", status_code=400)
        if resp.status_code != 200:
            raise CalculatorError(f"Skaičiuoklės paslauga grąžino klaidą (HTTP {resp.status_code}).",
                                  status_code=resp.status_code)
        return json.loads(resp.text, parse_float=Decimal)
