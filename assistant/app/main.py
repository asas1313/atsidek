"""Atsidėk AI asistentas: atskiras servisas. Vartotojas -> šis servisas -> Atsidėk /api/v1/compare (Java).

Pagrindinė taisyklė: modelis niekada neskaičiuoja mokesčių. Jis tik ištraukia skaičius iš teksto,
o mokesčius skaičiuoja deterministinis Atsidėk variklis per HTTP.
"""
import logging
import os
import time
from decimal import Decimal
from pathlib import Path
from typing import Any, Optional

import anthropic
from fastapi import FastAPI, Form, HTTPException, Request
from fastapi.responses import HTMLResponse
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel

from .agent_loop import run_agent_loop
from .calculator import AtsidekApi, Calculator, CalculatorError
from .chat_limiter import ChatLimiter
from .chat_limiter import Decision as ChatDecision
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
from .response_builder import build_response
from .savings_tool import TOOL_DEF as SAVINGS_TOOL_DEF
from .savings_tool import TOOL_NAME as SAVINGS_TOOL_NAME
from .savings_tool import make_calculate_savings_plan_tool
from .system_prompt import SYSTEM_PROMPT
from .tax_tool import TOOL_DEF as TAX_TOOL_DEF
from .tax_tool import TOOL_NAME as TAX_TOOL_NAME
from .tax_tool import make_calculate_iv_taxes_tool
from .tools import ToolRegistry
from .usage_logging import log_usage

logger = logging.getLogger(__name__)

CONTEXT_TOKEN_HEADER = "X-Atsidek-Context-Token"

MAX_LENGTH = 1000
MAX_AMOUNT = Decimal("100000000")
GOALS_MAX_LENGTH = 1000

# B7 wiring: real agent path's own friendly, Lithuanian, never-a-stack-trace replies (owner
# instruction) for the handled-failure cases that never reach the model/tools at all.
AGENT_CHAT_UNCONFIGURED_REPLY = "AI asistentas nesukonfigūruotas (trūksta ANTHROPIC_API_KEY)."
AGENT_CHAT_UNAVAILABLE_REPLY = "AI asistentas laikinai nepasiekiamas. Pabandykite vėliau."
AGENT_CHAT_ANON_LIMIT_REPLY = (
    "Šiandienos AI pokalbio limitas pasiektas. Prisijunkite, kad gautumėte didesnį limitą, arba "
    "pabandykite rytoj."
)
AGENT_CHAT_LOGGED_IN_LIMIT_REPLY = "Šiandienos AI pokalbio limitas pasiektas. Pabandykite rytoj."
# `build_response` can legitimately return "" (no tool was ever called successfully AND the model
# produced no text either, e.g. every tool call errored and the model gave up without a final
# text turn) - never send an empty reply to the user.
AGENT_CHAT_EMPTY_REPLY_FALLBACK = "Atsiprašome, nepavyko apdoroti užklausos. Pabandykite dar kartą."
# Caps how much conversation history is handed back to Java for session storage (owner decision
# 7: Java/HttpSession owns history, this service is stateless) - an unbounded per-session history
# would grow every turn for the lifetime of a browser session; this is a generous bound (~10
# user/assistant exchanges, each potentially with several tool_result turns interleaved), not a
# precisely-tuned one.
MAX_HISTORY_MESSAGES = 20


class GoalsParseRequest(BaseModel):
    text: str = ""


class InternalChatRequest(BaseModel):
    message: str = ""
    # B7 wiring: Java is the only source of truth for both (ADR-0003 - this service never decodes
    # the context token, so it cannot derive tier itself; `key` is the stable per-user identifier
    # chat_limiter.ChatLimiter needs for its per-tier daily counters). Both `None` for anonymous.
    tier: Optional[str] = None
    key: Optional[str] = None
    # Prior turns, Java-session-held (owner decision 7) - this service is itself stateless across
    # requests; the shape is exactly `AgentLoopResult.messages` round-tripped back unexamined, see
    # `run_agent_loop`'s own `history` parameter docstring.
    history: Optional[list[dict]] = None


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
               goals_limiter: Optional[GoalsAiLimiter] = None, agent_client: Optional[Any] = None,
               chat_limiter: Optional[ChatLimiter] = None,
               agent_chat_enabled: Optional[bool] = None) -> FastAPI:
    anthropic_api_key = os.getenv("ANTHROPIC_API_KEY", "")
    agent_model = os.getenv("AI_MODEL", "claude-haiku-5-5")
    extractor = extractor or ClaudeExtractor(anthropic_api_key, agent_model)
    calculator = calculator or AtsidekApi(os.getenv("ATSIDEK_API_URL", "http://app:8080"),
                                          os.getenv("ATSIDEK_API_KEY", ""))
    limiter = limiter or RateLimiter(int(os.getenv("AI_RATE_LIMIT_PER_HOUR", "10")),
                                     int(os.getenv("AI_DAILY_CAP", "300")))
    goals_extractor = goals_extractor or ClaudeGoalsExtractor(anthropic_api_key, agent_model)
    goals_limiter = goals_limiter or GoalsAiLimiter(int(os.getenv("GOALS_AI_DAILY_LIMIT", "500")))
    # B7 wiring: real agent path's own Anthropic client - deliberately separate from extractor's
    # (which may be a test fake/non-API-calling Extractor), same construction as ClaudeExtractor's
    # own `anthropic.Anthropic(...)` call above. `None` when no key is configured (never a client
    # that would just fail on first use) - the /internal/chat handler checks for this explicitly.
    if agent_client is None and anthropic_api_key:
        agent_client = anthropic.Anthropic(api_key=anthropic_api_key, timeout=30, max_retries=1)
    # Owner instruction: AGENT_CHAT_ENABLED (default false) selects the live chat behavior -
    # false keeps today's B1-era single-tool stub below byte-for-byte; true switches to the real
    # multi-step agent (B3 loop + B4 templates/system prompt + B5 limiter/cost logging).
    # ROLLBACK = flipping this flag back to false, nothing else. A caller may also pass it
    # directly (tests) instead of going through the env var.
    if agent_chat_enabled is None:
        agent_chat_enabled = os.getenv("AGENT_CHAT_ENABLED", "false").strip().lower() == "true"
    chat_limiter = chat_limiter or ChatLimiter(
        anonymous_daily_cap=int(os.getenv("CHAT_ANON_DAILY_CAP", "50")),
        tier_daily_caps={
            "FREE": int(os.getenv("CHAT_FREE_DAILY_CAP", "100")),
            "PREMIUM": int(os.getenv("CHAT_PREMIUM_DAILY_CAP", "500")),
        },
    )

    registry = ToolRegistry()
    registry.register(EXTRACT_TOOL_NAME, extractor.extract)
    # B1 (ADR-0003): calculator jau sukonfigūruotas aukščiau su ATSIDEK_API_KEY - čia tik
    # susaistoma su registru, tiksliai taip, kaip extractor.extract aukščiau.
    registry.register(TAX_TOOL_NAME, make_calculate_iv_taxes_tool(calculator))
    # B7 wiring: registered unconditionally (harmless when AGENT_CHAT_ENABLED=false - nothing
    # dispatches it) so the agent path never needs its own separate registry setup.
    registry.register(SAVINGS_TOOL_NAME, make_calculate_savings_plan_tool(calculator))
    agent_tools = [TAX_TOOL_DEF, SAVINGS_TOOL_DEF]

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

    def _agent_chat(body: InternalChatRequest, text: str, token: Optional[str]) -> dict:
        """B7 wiring: the real multi-step agent path (AGENT_CHAT_ENABLED=true) - tool registry
        (both tools), SYSTEM_PROMPT, run_agent_loop (B3), build_response (B4), chat_limiter (B5
        quota) and log_usage (B5 cost logging), all actually called together for the first time.

        Every expected failure (daily cap, unconfigured key, a Claude API error) returns a 200
        with a short, friendly Lithuanian `reply` instead of raising - owner instruction: never a
        stack trace to the user, always log. Only the already-existing 400s above (empty/too-long
        message) stay real HTTP errors, unchanged from the B1-era path.
        """
        decision = chat_limiter.try_acquire(body.key, body.tier)
        if decision is ChatDecision.DAILY_CAP:
            logger.warning("chat daily cap reached", extra={"tier": body.tier, "has_key": body.key is not None})
            reply = AGENT_CHAT_ANON_LIMIT_REPLY if not body.tier else AGENT_CHAT_LOGGED_IN_LIMIT_REPLY
            return {"reply": reply, "limited": True}

        if agent_client is None:
            logger.warning("agent chat called with no ANTHROPIC_API_KEY configured")
            return {"reply": AGENT_CHAT_UNCONFIGURED_REPLY}

        # Vienas RequestContext vienai gaunamai žinutei (B0 design decision 2), kaip ir /asistentas
        # - `key`/`tier` ateina iš Java (žr. InternalChatRequest), NIEKADA iš žetono dekodavimo.
        request_ctx = RequestContext(user_id=body.key, tier=body.tier or "anonymous")
        started = time.monotonic()
        try:
            result = run_agent_loop(
                agent_client, registry, agent_tools, request_ctx, token, text,
                history=body.history, system=SYSTEM_PROMPT, model=agent_model,
            )
        except (anthropic.APIStatusError, anthropic.APIError) as e:
            logger.exception("agent chat: Claude API error")
            return {"reply": AGENT_CHAT_UNAVAILABLE_REPLY}
        duration_ms = int((time.monotonic() - started) * 1000)

        log_usage(**result.usage, tool_call_count=len(result.tool_calls), duration_ms=duration_ms)

        reply = build_response(result.tool_calls, result.final_text) or AGENT_CHAT_EMPTY_REPLY_FALLBACK
        # Java/HttpSession persists this verbatim and hands it back as `history` next turn (owner
        # decision 7) - capped so one long-lived session's history can't grow unboundedly.
        # `stop_reason` (item 5): ChatController forwards this response as an opaque `Object`
        # (never reads past "messages" - see its own javadoc), so this extra field is harmless to
        # add - it exists purely so `run_eval.py --via-endpoint` can reconstruct the SAME
        # `response_complete` check the direct-call path already gets from `AgentLoopResult`
        # itself, which the HTTP response otherwise has no way to carry.
        return {
            "reply": reply,
            "messages": result.messages[-MAX_HISTORY_MESSAGES:],
            "stop_reason": result.stop_reason,
        }

    @app.post("/internal/chat")
    def internal_chat(request: Request, body: InternalChatRequest):
        """B1 (ADR-0003): naujo Java chat endpoint'o (`POST /chat/send`) vidinis tikslas.

        Kontekstinis žetonas ateina CONTEXT_TOKEN_HEADER antraštėje - šis servisas jo NEI tikrina,
        NEI dekoduoja (ADR-0003: Python tik relė), tik perduoda nepakeistą kiekvienam įrankiui,
        kuris jį toliau nepakeistą perduoda Java (žr. tax_tool.py/savings_tool.py/calculator.py).

        AGENT_CHAT_ENABLED=false (numatyta): B1-era stub žemiau, nepakeistas - įrodo, kad žetonas
        ir ištraukimo→įrankio vamzdynas per `registry` veikia, NE tikras agento ciklas. true: B7's
        `_agent_chat` virš - tikras daugiapakopis agento ciklas (B3), šablonas (B4), limitas ir
        kainos logas (B5), pokalbio istorija per Java sesiją (B5 sprendimas 7).
        """
        token = request.headers.get(CONTEXT_TOKEN_HEADER)
        text = (body.message or "").strip()
        if not text:
            raise HTTPException(400, "message privalomas")
        # Ta pati MAX_LENGTH riba kaip /asistentas (review round 1 SHOULD #2) - NE antra kopija su
        # savo skaičiumi, kad abu limitai negalėtų kada nors nebesutapti.
        if len(text) > MAX_LENGTH:
            raise HTTPException(400, f"Tekstas per ilgas (daugiausia {MAX_LENGTH} simbolių).")

        if agent_chat_enabled:
            return _agent_chat(body, text, token)

        # Vienas RequestContext vienai gaunamai žinutei (B0 design decision 2), kaip ir /asistentas.
        request_ctx = RequestContext(user_id=None, tier="anonymous")
        try:
            x = registry.dispatch(EXTRACT_TOOL_NAME, text)
        except ExtractionError as e:
            raise HTTPException(503, str(e)) from e
        if x.revenue is None:
            return {"reply": "Trūksta pajamų sumos skaičiavimui - patikslink tekstą.", "tool_result": None}
        tool_input = {
            "revenue": x.revenue,
            "actual_expenses": x.expenses,
            "other_income": x.other_income,
            "pension_accumulation": x.pillar2 is True,
            "psd_min_months": 12 if x.psd_months is None else max(0, min(12, x.psd_months)),
        }
        try:
            tool_result = registry.dispatch(TAX_TOOL_NAME, tool_input, request_ctx, token)
        except CalculatorError as e:
            # Review round 1 SHOULD #2: Java 400 (atmetė įvestį - kliento problema) != Java
            # nepasiekiama/5xx (upstream problema). CalculatorError.status_code == 400 ŽINOMAS
            # atvejis, kai Java aiškiai pasakė "bloga įvestis" - visa kita (None = tinklo klaida/
            # nesukonfigūruota, arba kitas HTTP kodas) laikoma upstream klaida, kaip ir anksčiau.
            if e.status_code == 400:
                raise HTTPException(400, str(e)) from e
            raise HTTPException(502, str(e)) from e
        return {"reply": "ack", "tool_result": tool_result}

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
