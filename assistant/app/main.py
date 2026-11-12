"""Atsidėk AI asistentas: atskiras servisas. Vartotojas -> šis servisas -> Atsidėk /api/v1/compare (Java).

Pagrindinė taisyklė: modelis niekada neskaičiuoja mokesčių. Jis tik ištraukia skaičius iš teksto,
o mokesčius skaičiuoja deterministinis Atsidėk variklis per HTTP.
"""
import os
from decimal import Decimal
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, Form, HTTPException, Request
from fastapi.responses import HTMLResponse
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel

from .calculator import AtsidekApi, Calculator, CalculatorError
from .extractor import TOOL as EXTRACT_TOOL_NAME
from .extractor import ClaudeExtractor, ExtractionError, Extractor
from .goals_extractor import (
    ClaudeGoalsExtractor,
    GoalsExtractionError,
    GoalsExtractor,
    build_clarifying_question,
)
from .goals_limiter import Decision as GoalsDecision
from .goals_limiter import GoalsAiLimiter
from .limiter import Decision, RateLimiter
from .request_context import RequestContext
from .tools import ToolRegistry

MAX_LENGTH = 1000
MAX_AMOUNT = Decimal("100000000")
GOALS_MAX_LENGTH = 1000


class GoalsParseRequest(BaseModel):
    text: str = ""


def eur(v) -> str:
    """1234.5 -> '1 234,50' (kaip Thymeleaf WHITESPACE/COMMA)."""
    return f"{Decimal(v):,.2f}".replace(",", " ").replace(".", ",")


def pct(v) -> str:
    return f"{Decimal(v) * 100:,.1f}".replace(",", " ").replace(".", ",")


def client_ip(request: Request) -> str:
    """Už Cloudflare / proxy: tikrasis kliento adresas iš antraštės, kitaip tiesioginis."""
    cf = request.headers.get("CF-Connecting-IP", "").strip()
    if cf:
        return cf
    xff = request.headers.get("X-Forwarded-For", "").strip()
    if xff:
        return xff.split(",")[0].strip()
    return request.client.host if request.client else "unknown"


def _invalid(v: Decimal) -> bool:
    return v < 0 or v > MAX_AMOUNT


def analyse(text: str, registry: ToolRegistry, calculator: Calculator, request_ctx: RequestContext) -> dict:
    """Grąžina šablono kontekstą: x (ką supratau), c (palyginimas), r (rodomas rezultatas) arba error.

    Ekstraktorius kviečiamas per `registry` pagal pavadinimą (B0 - įrankio registras), o ne
    tiesiogiai; rezultatas papildomai saugomas `request_ctx` (B0 - vienos žinutės kontekstas).
    Elgsena vartotojui nesikeičia - tai tik B1/B3 pravažiavimo vamzdynas.
    """
    ctx: dict = {}
    try:
        x = registry.dispatch(EXTRACT_TOOL_NAME, text)
    except ExtractionError as e:
        return {"error": str(e)}
    request_ctx.store_result(x)
    ctx["x"] = x
    if x.revenue is None:
        return {**ctx, "error": "Be pajamų sumos paskaičiuoti negalima."}
    if _invalid(x.revenue) or (x.expenses is not None and _invalid(x.expenses)):
        return {**ctx, "error": "Ištrauktos sumos neatrodo tinkamos (neigiamos arba per didelės). Patikslink tekstą."}
    if x.other_income is not None and _invalid(x.other_income):
        return {**ctx, "error": "Ištrauktos kitos pajamos neatrodo tinkamos. Patikslink tekstą."}

    expenses = x.expenses if x.expenses is not None else Decimal(0)
    other = x.other_income if x.other_income is not None else Decimal(0)
    pillar2 = x.pillar2 is True
    psd_months = 12 if x.psd_months is None else max(0, min(12, x.psd_months))
    try:
        c = calculator.compare(x.revenue, expenses, other, pillar2, psd_months)
    except CalculatorError as e:
        return {**ctx, "error": str(e)}

    has_actual = expenses > 0
    if not has_actual or x.expense_method == "30_proc":
        actual_shown = False
    elif x.expense_method == "faktines":
        actual_shown = True
    else:
        actual_shown = c["better"]["method"] == "ACTUAL"
    return {**ctx, "other": other, "pillar2": pillar2, "psd_months": psd_months, "c": c,
            "has_actual": has_actual, "actual_shown": actual_shown,
            "r": c["actual"] if actual_shown else c["flat"]}


def create_app(extractor: Optional[Extractor] = None, calculator: Optional[Calculator] = None,
               limiter: Optional[RateLimiter] = None, goals_extractor: Optional[GoalsExtractor] = None,
               goals_limiter: Optional[GoalsAiLimiter] = None) -> FastAPI:
    extractor = extractor or ClaudeExtractor(os.getenv("ANTHROPIC_API_KEY", ""),
                                             os.getenv("AI_MODEL", "claude-haiku-5-5"))
    calculator = calculator or AtsidekApi(os.getenv("ATSIDEK_API_URL", "http://app:8080"),
                                          os.getenv("ATSIDEK_API_KEY", ""))
    limiter = limiter or RateLimiter(int(os.getenv("AI_RATE_LIMIT_PER_HOUR", "10")),
                                     int(os.getenv("AI_DAILY_CAP", "300")))
    goals_extractor = goals_extractor or ClaudeGoalsExtractor(os.getenv("ANTHROPIC_API_KEY", ""),
                                                               os.getenv("AI_MODEL", "claude-haiku-5-5"))
    goals_limiter = goals_limiter or GoalsAiLimiter(int(os.getenv("GOALS_AI_DAILY_LIMIT", "500")))

    registry = ToolRegistry()
    registry.register(EXTRACT_TOOL_NAME, extractor.extract)

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    templates = Jinja2Templates(directory=str(Path(__file__).resolve().parent.parent / "templates"))
    templates.env.filters["eur"] = eur
    templates.env.filters["pct"] = pct

    def render(request: Request, name: str, ctx: dict) -> HTMLResponse:
        return templates.TemplateResponse(request, name, ctx)

    @app.get("/healthz")
    def healthz():
        return {"status": "ok"}

    @app.post("/asistentas", response_class=HTMLResponse)
    def ask(request: Request, text: str = Form("")):
        trimmed = (text or "").strip()
        ctx: dict = {"text": trimmed, "asked": True}
        if not trimmed:
            ctx["error"] = "Parašyk, kokia tavo situacija."
        elif len(trimmed) > MAX_LENGTH:
            ctx["error"] = f"Tekstas per ilgas (daugiausia {MAX_LENGTH} simbolių)."
        else:
            d = limiter.try_acquire(client_ip(request))
            if d is Decision.ALLOWED:
                # Vienas RequestContext vienai gaunamai žinutei (B0 design decision 2) - niekada
                # modulio lygio/bendras tarp užklausų.
                request_ctx = RequestContext(user_id=None, tier="anonymous")
                ctx.update(analyse(trimmed, registry, calculator, request_ctx))
            elif d is Decision.IP_LIMIT:
                ctx["error"] = "Per daug užklausų iš tavo adreso. Pabandyk vėliau arba naudok skaičiuoklę."
            else:
                ctx["error"] = "Šiandien AI asistento limitas išnaudotas. Pabandyk rytoj arba naudok skaičiuoklę."
        # Puslapį (meniu, forma) rodo Java; čia visada tik rezultato fragmentas, kurį htmx įdeda į #result
        return render(request, "result.html", ctx)

    @app.post("/api/v1/goals/parse")
    def goals_parse(body: GoalsParseRequest):
        # Viešas endpoint'as (žr. PLAN.md K2) - be API rakto, saugomas tik globaliu dienos limitu.
        # Laisvas tekstas į atsakymą/logus nerašomas - tik ištraukta struktūra žemiau.
        text = (body.text or "").strip()
        if not text:
            raise HTTPException(400, "Parašykite, koks jūsų taupymo tikslas.")
        if len(text) > GOALS_MAX_LENGTH:
            raise HTTPException(400, f"Tekstas per ilgas (daugiausia {GOALS_MAX_LENGTH} simbolių).")
        if goals_limiter.try_acquire() is not GoalsDecision.ALLOWED:
            raise HTTPException(
                503, "AI laikinai neprieinamas (pasiektas dienos limitas). Formą galima užpildyti ranka.")
        try:
            x = goals_extractor.extract(text)
        except GoalsExtractionError as e:
            raise HTTPException(503, str(e)) from e
        return {
            "goalType": x.goal_type,
            "mode": x.mode,
            "amount": float(x.amount) if x.amount is not None else None,
            "years": x.years,
            "targetYear": x.target_year,
            "targetMonth": x.target_month,
            "childCurrentAge": x.child_current_age,
            "childTargetAge": x.child_target_age,
            "monthlyContribution": float(x.monthly_contribution) if x.monthly_contribution is not None else None,
            "members": x.members,
            "workers": x.workers,
            "income": float(x.income) if x.income is not None else None,
            "expenses": float(x.expenses) if x.expenses is not None else None,
            "confidence": x.confidence,
            "missingCritical": x.missing,
            "clarifyingQuestion": build_clarifying_question(x.missing, x.mode),
        }

    return app


app = create_app()
