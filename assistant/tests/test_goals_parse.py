"""CI "smoke" testai POST /api/v1/goals/parse maršrutui - FAKE Claude klientas (jokio tikro
ANTHROPIC_API_KEY), tikrina maršruto elgesį: laukų grąžinimą, clarifyingQuestion/missingCritical
logiką, dienos limito viršijimą, teksto ilgio limitą. Pilnas (>=60 formuluočių) eval su tikru API -
assistant/evals/goals_parse/ (paleidžiamas rankiniu būdu/atskiru CI workflow, ne čia)."""
from decimal import Decimal

from fastapi.testclient import TestClient

from app.goals_extractor import GoalExtraction, GoalsExtractionError
from app.goals_limiter import Decision, GoalsAiLimiter
from app.main import create_app


class FakeGoalsExtractor:
    def __init__(self, x=None, error=None):
        self.x, self.error, self.texts = x, error, []

    def extract(self, text, today=None):
        self.texts.append(text)
        if self.error:
            raise GoalsExtractionError(self.error)
        return self.x


def client(x=None, extractor=None, goals_limiter=None):
    app = create_app(goals_extractor=extractor or FakeGoalsExtractor(x), goals_limiter=goals_limiter)
    return TestClient(app)


def parse(c, text="Noriu sutaupyti 20000 eurų per 5 metus"):
    return c.post("/api/v1/goals/parse", json={"text": text})


def complete_extraction(**overrides) -> GoalExtraction:
    base = dict(goal_type="CHILD", mode="YEARS", amount=Decimal("20000"), years=5,
                confidence={"goalType": 0.9, "mode": 0.9, "amount": 0.9, "years": 0.9})
    base.update(overrides)
    x = GoalExtraction(**base)
    return x


def test_returns_extracted_fields_and_no_clarifying_question_when_complete():
    r = parse(client(complete_extraction()))
    assert r.status_code == 200
    body = r.json()
    assert body["goalType"] == "CHILD" and body["mode"] == "YEARS"
    assert body["amount"] == 20000.0 and body["years"] == 5
    assert body["missingCritical"] == [] and body["clarifyingQuestion"] is None


def test_missing_amount_triggers_clarifying_question():
    x = complete_extraction(amount=None, missing=["amount"])
    body = parse(client(x)).json()
    assert body["missingCritical"] == ["amount"]
    assert body["clarifyingQuestion"]["field"] == "amount"
    assert 2 <= len(body["clarifyingQuestion"]["options"]) <= 4


def test_missing_term_for_mode_triggers_clarifying_question():
    x = complete_extraction(mode="CHILD_AGE", years=None, child_current_age=4, child_target_age=None,
                             confidence={"goalType": 0.9, "mode": 0.9, "amount": 0.9, "childCurrentAge": 0.9},
                             missing=["childTargetAge"])
    body = parse(client(x)).json()
    assert body["missingCritical"] == ["childTargetAge"]
    assert body["clarifyingQuestion"]["field"] == "childTargetAge"


def test_low_confidence_also_triggers_clarifying_question():
    x = complete_extraction(confidence={"goalType": 0.9, "mode": 0.9, "amount": 0.4, "years": 0.9},
                             missing=["amount"])
    body = parse(client(x)).json()
    assert "amount" in body["missingCritical"] and body["clarifyingQuestion"]["field"] == "amount"


def test_household_and_decimal_fields_are_json_serializable_numbers():
    x = complete_extraction(members=4, workers=2, income=Decimal("2000"), expenses=Decimal("1500"))
    body = parse(client(x)).json()
    assert body["members"] == 4 and body["workers"] == 2
    assert body["income"] == 2000.0 and body["expenses"] == 1500.0


def test_empty_text_rejected_before_calling_model():
    ex = FakeGoalsExtractor(complete_extraction())
    r = client(extractor=ex).post("/api/v1/goals/parse", json={"text": "   "})
    assert r.status_code == 400 and ex.texts == []


def test_too_long_text_rejected_before_calling_model():
    ex = FakeGoalsExtractor(complete_extraction())
    r = client(extractor=ex).post("/api/v1/goals/parse", json={"text": "a" * 1001})
    assert r.status_code == 400 and ex.texts == []


def test_extractor_error_returns_503():
    r = parse(client(extractor=FakeGoalsExtractor(error="Nepavyko pasiekti AI paslaugos.")))
    assert r.status_code == 503 and "AI paslaugos" in r.json()["detail"]


def test_daily_limit_exceeded_returns_503_and_form_still_usable_without_ai():
    limiter = GoalsAiLimiter(1, clock=lambda: __import__("datetime").date(2026, 11, 10))
    ex = FakeGoalsExtractor(complete_extraction())
    c = client(extractor=ex, goals_limiter=limiter)
    assert parse(c).status_code == 200
    r2 = parse(c)
    assert r2.status_code == 503 and "AI laikinai neprieinamas" in r2.json()["detail"]
    assert len(ex.texts) == 1  # antras kvietimas sustabdytas limiteryje, modelio NEŠAUKĖ


def test_goals_limiter_decision_enum():
    limiter = GoalsAiLimiter(1)
    assert limiter.try_acquire() is Decision.ALLOWED
    assert limiter.try_acquire() is Decision.DAILY_LIMIT


def test_goals_limiter_resets_next_day():
    from datetime import date as real_date

    day = [1]
    limiter = GoalsAiLimiter(1, clock=lambda: real_date(2026, 11, day[0]))
    assert limiter.try_acquire() is Decision.ALLOWED
    assert limiter.try_acquire() is Decision.DAILY_LIMIT
    day[0] = 2
    assert limiter.try_acquire() is Decision.ALLOWED
