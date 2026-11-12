import json
from decimal import Decimal

import httpx
from fastapi.testclient import TestClient

from app.calculator import AtsidekApi, CalculatorError
from app.extractor import Extraction, ExtractionError, parse
from app.limiter import Decision, RateLimiter
from app.main import create_app, eur


def result(total, monthly="100.00"):
    d = Decimal
    return {"ruleYear": 2026, "method": "FLAT_30", "revenue": d(0), "expenses": d(0), "taxableIncome": d("1000.00"),
            "gpm": d("10.00"), "extraGpm": d(0), "vsd": d("20.00"), "psd": d("30.00"), "pensionAccumulation": d(0),
            "total": d(str(total)), "monthlyReserve": d(monthly), "effectiveRate": d("0.2500")}


def compare_json(better="FLAT_30"):
    return {"flat": result("500.50"), "actual": result("600.25"), "better": {**result("500.50"), "method": better}}


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
        self.response, self.error, self.calls = response or compare_json(), error, []

    def compare(self, *args):
        self.calls.append(args)
        if self.error:
            raise CalculatorError(self.error)
        return self.response


def client(x=None, calc=None, extractor=None, limiter=None):
    app = create_app(extractor or FakeExtractor(x), calc or FakeCalc(), limiter or RateLimiter(100, 100))
    return TestClient(app)


def ask(c, text="Gavau 2400 €", htmx=True):
    return c.post("/asistentas", data={"text": text}, headers={"HX-Request": "true"} if htmx else {})


def test_calls_api_with_extracted_values_and_shows_result():
    calc = FakeCalc()
    c = client(Extraction(revenue=Decimal("2400"), expenses=Decimal("300")), calc)
    r = ask(c)
    assert r.status_code == 200 and "500,50" in r.text and "Atsidėk kas mėnesį" in r.text
    assert "<html" not in r.text  # htmx grąžina tik fragmentą
    assert calc.calls == [(Decimal("2400"), Decimal("300"), Decimal(0), False, 12)]


def test_always_returns_fragment_even_without_htmx():
    r = ask(client(Extraction(revenue=Decimal("100"))), htmx=False)
    assert "<html" not in r.text and "Rezultatas" in r.text


def test_better_actual_is_shown_when_unspecified():
    calc = FakeCalc(compare_json(better="ACTUAL"))
    r = ask(client(Extraction(revenue=Decimal("1000"), expenses=Decimal("900")), calc))
    assert "600,25" in r.text and "faktines išlaidas" in r.text


def test_explicit_30_percent_wins():
    calc = FakeCalc(compare_json(better="ACTUAL"))
    x = Extraction(revenue=Decimal("1000"), expenses=Decimal("900"), expense_method="30_proc")
    assert "pagal 30 % normą" in ask(client(x, calc)).text


def test_extra_params_forwarded_and_psd_clamped():
    calc = FakeCalc()
    x = Extraction(revenue=Decimal("1000"), other_income=Decimal("5000"), pillar2=True, psd_months=99)
    ask(client(x, calc))
    assert calc.calls[0] == (Decimal("1000"), Decimal(0), Decimal("5000"), True, 12)


def test_missing_revenue_does_not_call_api():
    calc = FakeCalc()
    r = ask(client(Extraction(missing=["pajamos"]), calc))
    assert "Be pajamų" in r.text and "pajamos" in r.text and calc.calls == []


def test_negative_or_huge_amounts_rejected_before_api():
    calc = FakeCalc()
    assert "neatrodo tinkamos" in ask(client(Extraction(revenue=Decimal("-5")), calc)).text
    assert "neatrodo tinkamos" in ask(client(Extraction(revenue=Decimal("1e12")), calc)).text
    assert calc.calls == []


def test_extractor_and_calculator_errors_shown():
    assert "nesukonfigūruotas" in ask(client(extractor=FakeExtractor(error="AI asistentas nesukonfigūruotas"))).text
    r = ask(client(Extraction(revenue=Decimal("1")), FakeCalc(error="Nepavyko pasiekti skaičiuoklės paslaugos.")))
    assert "Nepavyko pasiekti skaičiuoklės" in r.text and "Ką supratau" in r.text


def test_empty_and_too_long_text_never_reach_model():
    ex = FakeExtractor(Extraction(revenue=Decimal("1")))
    c = client(extractor=ex)
    assert "Parašyk" in ask(c, "   ").text
    assert "per ilgas" in ask(c, "a" * 1001).text
    assert ex.texts == []


def test_rate_limit_per_ip():
    ex = FakeExtractor(Extraction(revenue=Decimal("1")))
    c = client(extractor=ex, limiter=RateLimiter(2, 100))
    ask(c), ask(c)
    assert "Per daug užklausų" in ask(c).text and len(ex.texts) == 2


def test_cf_connecting_ip_is_used_for_limit():
    c = client(Extraction(revenue=Decimal("1")), limiter=RateLimiter(1, 100))
    h = lambda ip: {"HX-Request": "true", "CF-Connecting-IP": ip}
    c.post("/asistentas", data={"text": "a"}, headers=h("1.1.1.1"))
    assert "Per daug" in c.post("/asistentas", data={"text": "a"}, headers=h("1.1.1.1")).text
    assert "Per daug" not in c.post("/asistentas", data={"text": "a"}, headers=h("2.2.2.2")).text


def test_limiter_window_and_daily_cap():
    t = [1_000_000.0]
    lim = RateLimiter(1, 2, clock=lambda: t[0])
    assert lim.try_acquire("a") is Decision.ALLOWED
    assert lim.try_acquire("a") is Decision.IP_LIMIT
    assert lim.try_acquire("b") is Decision.ALLOWED
    assert lim.try_acquire("c") is Decision.DAILY_CAP
    t[0] += 3601 + 86400
    assert lim.try_acquire("a") is Decision.ALLOWED


def test_parse_tool_input():
    x = parse({"pajamos_eur": 2400, "islaidos_eur": None, "islaidu_budas": "faktines", "laikotarpis": "",
               "kitos_pajamos_eur": 12000.5, "pensiju_kaupimas": True, "psd_menesiai": 6,
               "trukstama_info": ["išlaidos"]})
    assert (x.revenue, x.expenses, x.other_income) == (Decimal("2400"), None, Decimal("12000.5"))
    assert x.pillar2 is True and x.psd_months == 6 and x.missing == ["išlaidos"]
    assert parse({"pajamos_eur": True, "pensiju_kaupimas": "taip"}).revenue is None


def test_eur_format():
    assert eur(Decimal("1234.5")) == "1 234,50"


def test_api_client_sends_key_and_parses_decimals():
    seen = {}

    def handler(request: httpx.Request):
        seen["key"] = request.headers["x-api-key"]
        seen["path"] = request.url.path
        seen["body"] = json.loads(request.content)
        seen["headers"] = request.headers
        return httpx.Response(200, text='{"flat":{"total":1.10}}')

    api = AtsidekApi("http://app:8080", "atsk_x", httpx.MockTransport(handler))
    out = api.compare(Decimal("2400"), Decimal("300"), None, True, 12)
    assert seen["key"] == "atsk_x" and seen["path"] == "/api/v1/compare"
    assert seen["body"] == {"ivRevenue": 2400.0, "actualExpenses": 300.0, "otherIncome": 0.0,
                            "pensionAccumulation": True, "psdMinMonths": 12}
    assert out["flat"]["total"] == Decimal("1.10")
    # B1/ADR-0003: be žetono (šiandieninis /asistentas kelias) antraštė VISIŠKAI nesiunčiama, ne
    # tuščia reikšmė - "zero behaviour change" egzistuojantiems kvietimams.
    assert "x-atsidek-context-token" not in seen["headers"]


def test_api_client_forwards_context_token_header_unmodified_when_given():
    seen = {}

    def handler(request: httpx.Request):
        seen["token_header"] = request.headers.get("x-atsidek-context-token")
        return httpx.Response(200, text='{"flat":{"total":1.10}}')

    api = AtsidekApi("http://app:8080", "atsk_x", httpx.MockTransport(handler))
    token = "eyJhbGciOi.somesignature"
    api.compare(Decimal("2400"), Decimal("300"), None, True, 12, context_token=token)
    assert seen["token_header"] == token


def test_api_client_errors():
    def api(status):
        return AtsidekApi("http://x", "k", httpx.MockTransport(lambda r: httpx.Response(status, text="{}")))

    for status, text in [(400, "atmetė"), (401, "HTTP 401"), (500, "HTTP 500")]:
        try:
            api(status).compare(Decimal(1), None, None, False, 12)
            assert False
        except CalculatorError as e:
            assert text in str(e)
    try:
        AtsidekApi("http://x", "").compare(Decimal(1), None, None, False, 12)
        assert False
    except CalculatorError as e:
        assert "ATSIDEK_API_KEY" in str(e)


def test_savings_plan_client_sends_money_as_strings_not_floats():
    """B2 mechanical part: unlike compare()'s _json_default (float), savings_plan() must encode
    Decimal money as JSON strings - avoids float round-trip precision loss, and Jackson's
    BigDecimalDeserializer accepts a JSON string for a declared BigDecimal field just fine."""
    seen = {}

    def handler(request: httpx.Request):
        seen["path"] = request.url.path
        seen["body"] = json.loads(request.content)
        seen["headers"] = request.headers
        return httpx.Response(200, text='{"tier":"FREE","core":{},"locked_features":[]}')

    api = AtsidekApi("http://app:8080", "atsk_x", httpx.MockTransport(handler))
    goal = {"type": "OTHER", "mode": "YEARS", "amount": "50000", "years": 10}
    out = api.savings_plan(goal, Decimal("1500.50"), Decimal("200.25"), None, None, None)

    assert seen["path"] == "/api/v1/savings/plan"
    assert seen["body"] == {
        "goal": goal, "net_monthly_income": "1500.50", "monthly_tax_setaside": "200.25",
        "monthly_expenses": None, "household": None, "assumptions": None,
    }
    assert out["tier"] == "FREE"
    assert "x-atsidek-context-token" not in seen["headers"]


def test_savings_plan_client_does_not_substitute_zero_for_missing_tax_setaside_or_expenses():
    """monthly_tax_setaside/monthly_expenses have DIFFERENT Java-side null semantics (nz()->0 vs.
    stat_defaults resolution, SavingsPlanRequest javadoc) - this client must pass None through
    unchanged, never invent a 0 itself (that decision belongs to Java, not this client)."""
    seen = {}

    def handler(request: httpx.Request):
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, text='{"tier":"FREE","core":{},"locked_features":[]}')

    api = AtsidekApi("http://app:8080", "atsk_x", httpx.MockTransport(handler))
    api.savings_plan({"type": "OTHER", "mode": "YEARS", "amount": "1"}, Decimal("1000"),
                      None, None, None, None)

    assert seen["body"]["monthly_tax_setaside"] is None
    assert seen["body"]["monthly_expenses"] is None


def test_savings_plan_client_forwards_context_token_unmodified_when_given():
    seen = {}

    def handler(request: httpx.Request):
        seen["token_header"] = request.headers.get("x-atsidek-context-token")
        return httpx.Response(200, text='{"tier":"FREE","core":{},"locked_features":[]}')

    api = AtsidekApi("http://app:8080", "atsk_x", httpx.MockTransport(handler))
    token = "eyJhbGciOi.somesignature"
    api.savings_plan({"type": "OTHER", "mode": "YEARS", "amount": "1"}, Decimal("1000"),
                      None, None, None, None, context_token=token)
    assert seen["token_header"] == token


def test_savings_plan_client_sends_full_nested_body_with_java_matching_field_names():
    """Cross-checks the FULL request shape (goal/household/assumptions all populated, not just the
    minimal cases the other savings_plan client tests use) against SavingsPlanRequest.java's exact
    field names, including the nested GoalRequest/HouseholdRequest/AssumptionsRequest records."""
    seen = {}

    def handler(request: httpx.Request):
        seen["body"] = json.loads(request.content)
        return httpx.Response(200, text='{"tier":"FREE","core":{},"locked_features":[]}')

    api = AtsidekApi("http://app:8080", "atsk_x", httpx.MockTransport(handler))
    goal = {
        "type": "HOME_DOWN_PAYMENT", "mode": "YEARS", "amount": "15000", "years": 5,
        "target_year": None, "target_month": None, "child_current_age": None,
        "child_target_age": None, "monthly_contribution": None,
    }
    household = {"adults": 2, "children": 2}
    assumptions = {"annual_return_pct": Decimal("3"), "annual_inflation_pct": Decimal("2.5")}

    api.savings_plan(goal, Decimal("3100"), Decimal("900"), Decimal("1350"), household, assumptions)

    assert seen["body"] == {
        "goal": goal,
        "net_monthly_income": "3100",
        "monthly_tax_setaside": "900",
        "monthly_expenses": "1350",
        "household": {"adults": 2, "children": 2},
        "assumptions": {"annual_return_pct": "3", "annual_inflation_pct": "2.5"},
    }


def test_savings_plan_client_errors():
    def api(status):
        return AtsidekApi("http://x", "k", httpx.MockTransport(lambda r: httpx.Response(status, text="{}")))

    goal = {"type": "OTHER", "mode": "YEARS", "amount": "1"}
    for status, text in [(400, "atmetė"), (401, "HTTP 401"), (500, "HTTP 500")]:
        try:
            api(status).savings_plan(goal, Decimal(1000), None, None, None, None)
            assert False
        except CalculatorError as e:
            assert text in str(e)
    try:
        AtsidekApi("http://x", "").savings_plan(goal, Decimal(1000), None, None, None, None)
        assert False
    except CalculatorError as e:
        assert "ATSIDEK_API_KEY" in str(e)
