"""Atsidėk AI asistentas: atskiras servisas. Vartotojas -> šis servisas -> Atsidėk /api/v1/compare (Java).

Pagrindinė taisyklė: modelis niekada neskaičiuoja mokesčių. Jis tik ištraukia skaičius iš teksto,
o mokesčius skaičiuoja deterministinis Atsidėk variklis per HTTP.
"""
import os
from decimal import Decimal
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, Form, Header, Request
from fastapi.responses import HTMLResponse
from fastapi.templating import Jinja2Templates

from .calculator import AtsidekApi, Calculator, CalculatorError
from .extractor import ClaudeExtractor, ExtractionError, Extractor
from .limiter import Decision, RateLimiter

MAX_LENGTH = 1000
MAX_AMOUNT = Decimal("100000000")


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


def analyse(text: str, extractor: Extractor, calculator: Calculator) -> dict:
    """Grąžina šablono kontekstą: x (ką supratau), c (palyginimas), r (rodomas rezultatas) arba error."""
    ctx: dict = {}
    try:
        x = extractor.extract(text)
    except ExtractionError as e:
        return {"error": str(e)}
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
               limiter: Optional[RateLimiter] = None) -> FastAPI:
    extractor = extractor or ClaudeExtractor(os.getenv("ANTHROPIC_API_KEY", ""),
                                             os.getenv("AI_MODEL", "claude-haiku-5-5"))
    calculator = calculator or AtsidekApi(os.getenv("ATSIDEK_API_URL", "http://app:8080"),
                                          os.getenv("ATSIDEK_API_KEY", ""))
    limiter = limiter or RateLimiter(int(os.getenv("AI_RATE_LIMIT_PER_HOUR", "10")),
                                     int(os.getenv("AI_DAILY_CAP", "300")))

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    templates = Jinja2Templates(directory=str(Path(__file__).resolve().parent.parent / "templates"))
    templates.env.filters["eur"] = eur
    templates.env.filters["pct"] = pct
    templates.env.globals["app_version"] = "Versija " + os.getenv("APP_VERSION", "dev")
    templates.env.globals["build_sha"] = os.getenv("GIT_SHA", "")

    def render(request: Request, name: str, ctx: dict) -> HTMLResponse:
        return templates.TemplateResponse(request, name, ctx)

    @app.get("/", response_class=HTMLResponse)
    def page(request: Request):
        return render(request, "assistant.html", {"text": "", "asked": False})

    @app.get("/healthz")
    def healthz():
        return {"status": "ok"}

    @app.post("/asistentas", response_class=HTMLResponse)
    def ask(request: Request, text: str = Form(""), hx_request: Optional[str] = Header(None)):
        trimmed = (text or "").strip()
        ctx: dict = {"text": trimmed, "asked": True}
        if not trimmed:
            ctx["error"] = "Parašyk, kokia tavo situacija."
        elif len(trimmed) > MAX_LENGTH:
            ctx["error"] = f"Tekstas per ilgas (daugiausia {MAX_LENGTH} simbolių)."
        else:
            d = limiter.try_acquire(client_ip(request))
            if d is Decision.ALLOWED:
                ctx.update(analyse(trimmed, extractor, calculator))
            elif d is Decision.IP_LIMIT:
                ctx["error"] = "Per daug užklausų iš tavo adreso. Pabandyk vėliau arba naudok skaičiuoklę."
            else:
                ctx["error"] = "Šiandien AI asistento limitas išnaudotas. Pabandyk rytoj arba naudok skaičiuoklę."
        # htmx: tik rezultato fragmentas; be htmx - visas puslapis
        return render(request, "result.html" if hx_request else "assistant.html", ctx)

    return app


app = create_app()
