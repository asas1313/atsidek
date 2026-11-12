"""POST /internal/chat (B1): minimal endpoint the new Java chat endpoint calls, proving the
context token + registry-dispatched calculate_iv_taxes tool round-trip - NOT the real agent loop
(B3) and NOT the real multi-turn history (B5/decision 7).
"""
from decimal import Decimal

from fastapi.testclient import TestClient

from app.calculator import CalculatorError
from app.extractor import Extraction, ExtractionError
from app.limiter import RateLimiter
from app.main import create_app


class FakeExtractor:
    def __init__(self, x=None, error=None):
        self.x, self.error, self.texts = x, error, []

    def extract(self, text):
        self.texts.append(text)
        if self.error:
            raise ExtractionError(self.error)
        return self.x


class FakeCalc:
    def __init__(self, response=None, error=None):
        d = Decimal
        better = {"ruleYear": 2026, "method": "FLAT_30", "revenue": d(0), "expenses": d(0),
                  "taxableIncome": d(0), "gpm": d(0), "extraGpm": d(0), "vsd": d(0), "psd": d(0),
                  "pensionAccumulation": d(0), "total": d("500.50"), "monthlyReserve": d("41.71"),
                  "effectiveRate": d("0.25"), "netMonthlyIncome": d("458.79")}
        self.response = response or {"flat": better, "actual": better, "better": better}
        self.error = error
        self.calls = []

    def compare(self, revenue, actual_expenses, other_income, pension_accumulation, psd_min_months,
                context_token=None):
        self.calls.append((revenue, actual_expenses, other_income, pension_accumulation, psd_min_months,
                            context_token))
        if self.error:
            raise CalculatorError(self.error)
        return self.response


def client(x=None, calc=None, extractor=None):
    app = create_app(extractor or FakeExtractor(x), calc or FakeCalc(), RateLimiter(100, 100))
    return TestClient(app)


def test_token_is_forwarded_unmodified_and_tool_result_round_trips():
    calc = FakeCalc()
    c = client(Extraction(revenue=Decimal("2400"), expenses=Decimal("300")), calc)
    token = "eyJhbGciOi.deadbeef"

    r = c.post("/internal/chat", json={"message": "Gavau 2400 €"},
               headers={"X-Atsidek-Context-Token": token})

    assert r.status_code == 200
    body = r.json()
    assert body["tool_result"]["net_monthly_income"] == 458.79
    assert "tax_calc_id" in body["tool_result"]
    assert calc.calls[0][-1] == token


def test_missing_token_header_is_forwarded_as_none_not_rejected():
    calc = FakeCalc()
    c = client(Extraction(revenue=Decimal("1000")), calc)

    r = c.post("/internal/chat", json={"message": "Gavau 1000 €"})

    assert r.status_code == 200
    assert calc.calls[0][-1] is None


def test_blank_message_is_rejected_with_400():
    c = client(Extraction(revenue=Decimal("1000")))
    r = c.post("/internal/chat", json={"message": "   "})
    assert r.status_code == 400


def test_missing_revenue_returns_without_calling_the_tax_tool():
    calc = FakeCalc()
    c = client(Extraction(missing=["pajamos"]), calc)

    r = c.post("/internal/chat", json={"message": "Labas"})

    assert r.status_code == 200
    assert r.json()["tool_result"] is None
    assert calc.calls == []


def test_extraction_error_is_surfaced_as_503():
    extractor = FakeExtractor(error="Nepavyko ištraukti sumų iš teksto.")
    c = client(extractor=extractor, calc=FakeCalc())

    r = c.post("/internal/chat", json={"message": "Gavau kažkiek pinigų"})

    assert r.status_code == 503


def test_calculator_error_is_surfaced_as_502():
    calc = FakeCalc(error="Nepavyko pasiekti skaičiuoklės paslaugos.")
    c = client(Extraction(revenue=Decimal("1000")), calc)

    r = c.post("/internal/chat", json={"message": "Gavau 1000 €"})

    assert r.status_code == 502
