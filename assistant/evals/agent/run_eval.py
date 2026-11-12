#!/usr/bin/env python3
"""Live agent eval runner (B6, AC-B6-2/3/4/5/6). Same spirit as `evals/goals_parse/run_eval.py`.

SVARBU: this script calls the REAL Anthropic API (and a REAL Atsidek Java backend) and costs real
money. It is NEVER run automatically by `pytest` or on every PR's CI - per the plan's owner
decision 3, this ~30-case suite runs 3x per case ONLY manually or via a separate
`workflow_dispatch` CI job, on B3/B4/B6 PRs specifically. Every normal PR's test suite runs only
against fakes/mocks (see `tests/test_agent_loop.py` etc.) - zero live-LLM cost per PR.

Usage:
    cd assistant
    export ANTHROPIC_API_KEY=sk-...
    export ATSIDEK_API_KEY=...            # Java tool-callback endpoints require this
    export ATSIDEK_API_URL=http://localhost:8080   # see judgment-call note below
    python evals/agent/run_eval.py [--model claude-haiku-5-5] [--with-judge]

Judgment call on the Java base URL env var: `app/main.py`'s own default (`ATSIDEK_API_URL`,
defaulting to `http://app:8080`) is the docker-compose-internal hostname, not reachable from a
bare local script invocation - so this script reuses the SAME env var name (for consistency with
`main.py`/`calculator.py`, no new var invented) but defaults it to `http://localhost:8080`
(this repo's own README: `mvn spring-boot:run` serves at `http://localhost:8080`) when run
directly, not inside docker-compose.

`--with-judge` is OFF BY DEFAULT and additionally costly (an extra `claude-sonnet-5-5` call per
run) - see the loud warning this script prints when it is passed, and `judge_rubric.py`'s own
module docstring: that rubric/threshold are a DRAFT, not owner-approved yet.

Context-token judgment call: ADR-0003 context tokens are minted and signed by JAVA, not by this
Python service - this script cannot mint a real signed FREE/PREMIUM token itself. For
`variant="logged_in"` cases, it looks for a pre-minted token in `ATSIDEK_CONTEXT_TOKEN_FREE`/
`ATSIDEK_CONTEXT_TOKEN_PREMIUM` (the operator obtains these manually, e.g. via a real login,
before running this script); if absent, the case still runs but with no context token (same as
anon), so `score_anon_no_premium`-style premium-gating differences won't be exercised for that
case until a real token is supplied - this is logged in the per-case result, not silently hidden.

Rezultatas (per-case/per-group pass rate) įrašomas į evals/results/<YYYY-MM-DD>_agent_<modelis>.json.
"""
import argparse
import json
import os
import sys
from datetime import date
from pathlib import Path
from typing import Optional

ASSISTANT_DIR = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ASSISTANT_DIR))  # kad veiktų "from app...." importai
sys.path.insert(0, str(ASSISTANT_DIR.parent))  # kad veiktų "evals.agent...." importai

from app.agent_loop import run_agent_loop  # noqa: E402
from app.calculator import AtsidekApi  # noqa: E402
from app.request_context import RequestContext  # noqa: E402
from app.response_builder import build_response  # noqa: E402
from app.savings_tool import TOOL_DEF as SAVINGS_TOOL_DEF  # noqa: E402
from app.savings_tool import TOOL_NAME as SAVINGS_TOOL_NAME  # noqa: E402
from app.savings_tool import make_calculate_savings_plan_tool  # noqa: E402
from app.system_prompt import SYSTEM_PROMPT  # noqa: E402
from app.tax_tool import TOOL_DEF as TAX_TOOL_DEF  # noqa: E402
from app.tax_tool import TOOL_NAME as TAX_TOOL_NAME  # noqa: E402
from app.tax_tool import make_calculate_iv_taxes_tool  # noqa: E402
from app.tools import ToolRegistry  # noqa: E402
from evals.agent.cases import CASES  # noqa: E402
from evals.agent.judge_rubric import build_judge_request  # noqa: E402
from evals.agent.scoring import (  # noqa: E402
    score_anon_no_premium,
    score_missing_income_handled,
    score_no_banned_investment_terms,
    score_no_invented_numbers,
    score_tool_sequence,
)

RUNS_PER_CASE = 3  # owner decision 3: each case runs 3 times, reported as a pass RATE.


def _build_registry_and_tools(calculator):
    registry = ToolRegistry()
    registry.register(TAX_TOOL_NAME, make_calculate_iv_taxes_tool(calculator))
    registry.register(SAVINGS_TOOL_NAME, make_calculate_savings_plan_tool(calculator))
    return registry, [TAX_TOOL_DEF, SAVINGS_TOOL_DEF]


def _context_token_for(case) -> Optional[str]:
    if case.variant != "logged_in":
        return None
    env_var = f"ATSIDEK_CONTEXT_TOKEN_{case.tier}"
    return os.getenv(env_var)


def _run_one(client, registry, tools, case, model: str) -> dict:
    request_context = RequestContext(
        user_id="eval-user" if case.variant == "logged_in" else None,
        tier=case.tier if case.variant == "logged_in" else "anonymous",
    )
    context_token = _context_token_for(case)

    result = run_agent_loop(
        client,
        registry,
        tools,
        request_context,
        context_token,
        case.user_message,
        system=SYSTEM_PROMPT,
        model=model,
    )

    checks = {
        "tool_sequence": score_tool_sequence(result.tool_calls, case.expected_tool_sequence),
        "no_invented_numbers": score_no_invented_numbers(result.final_text, result.tool_calls),
        "anon_no_premium": score_anon_no_premium(result.tool_calls, case.variant),
        "no_banned_investment_terms": score_no_banned_investment_terms(result.final_text),
    }
    if case.expect_clarifying_question:
        checks["missing_income_handled"] = score_missing_income_handled(result.tool_calls, result.final_text)

    passed = all(checks.values())
    reply = build_response(result.tool_calls, result.final_text)
    return {
        "status": result.status,
        "final_text": result.final_text,
        "reply": reply,
        "tool_names_called": [c["name"] for c in result.tool_calls],
        "context_token_used": context_token is not None,
        "checks": checks,
        "passed": passed,
    }


def _run_judge(client, case, run_record: dict) -> dict:
    """Owner-gated, DRAFT (see judge_rubric.py) - only called when --with-judge is passed."""
    request = build_judge_request(case.user_message, run_record["reply"])
    response = client.messages.create(**request)
    text = "".join(block.text for block in response.content if block.type == "text")
    try:
        verdict = json.loads(text)
    except json.JSONDecodeError:
        return {"verdict": "FAIL", "reason": f"nevalidus judge JSON atsakymas: {text!r}"}
    return verdict


def run(model: str, with_judge: bool) -> dict:
    import anthropic

    client = anthropic.Anthropic()
    calculator = AtsidekApi(
        os.getenv("ATSIDEK_API_URL", "http://localhost:8080"),
        os.getenv("ATSIDEK_API_KEY", ""),
    )
    registry, tools = _build_registry_and_tools(calculator)

    case_results = []
    for case in CASES:
        runs = []
        for _ in range(RUNS_PER_CASE):
            run_record = _run_one(client, registry, tools, case, model)
            if with_judge:
                judge_verdict = _run_judge(client, case, run_record)
                run_record["judge_verdict"] = judge_verdict
                run_record["passed"] = run_record["passed"] and judge_verdict.get("verdict") == "PASS"
            runs.append(run_record)
        pass_rate = sum(1 for r in runs if r["passed"]) / len(runs)
        case_results.append({
            "id": case.id,
            "group": case.group,
            "variant": case.variant,
            "pass_rate": pass_rate,
            "runs": runs,
        })

    group_pass_rates: dict[str, float] = {}
    for group in {c.group for c in CASES}:
        rates = [cr["pass_rate"] for cr in case_results if cr["group"] == group]
        group_pass_rates[group] = sum(rates) / len(rates) if rates else None

    return {
        "model": model,
        "date": date.today().isoformat(),
        "runs_per_case": RUNS_PER_CASE,
        "with_judge": with_judge,
        "cases": len(CASES),
        "group_pass_rates": group_pass_rates,
        "overall_pass_rate": (
            sum(cr["pass_rate"] for cr in case_results) / len(case_results) if case_results else None
        ),
        "case_results": case_results,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", default=os.getenv("AI_MODEL", "claude-haiku-5-5"))
    parser.add_argument(
        "--with-judge",
        action="store_true",
        default=False,
        help=(
            "ALSO calls the DRAFT claude-sonnet-5-5 LLM-judge (judge_rubric.py) per run - extra "
            "live-API cost. The rubric wording/threshold are NOT owner-approved yet (see "
            "judge_rubric.py's module docstring)."
        ),
    )
    args = parser.parse_args()

    if not os.getenv("ANTHROPIC_API_KEY"):
        print("Reikia ANTHROPIC_API_KEY aplinkos kintamojo tikram eval'ui paleisti.", file=sys.stderr)
        sys.exit(1)

    if args.with_judge:
        print(
            "!!! DĖMESIO: --with-judge naudoja NEBAIGTĄ (DRAFT), savininko NEPATVIRTINTĄ "
            "rubriką/slenkstį (žr. judge_rubric.py) IR kainuoja papildomą claude-sonnet-5-5 "
            "kvietimą kiekvienam paleidimui. Rezultatus laikykite preliminariais, kol savininkas "
            "jų nepatvirtins. !!!",
            file=sys.stderr,
        )

    summary = run(args.model, args.with_judge)
    out_dir = ASSISTANT_DIR / "evals" / "results"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{summary['date']}_agent_{args.model}.json"
    out_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    overall = summary["overall_pass_rate"]
    print(f"Bendras pass rate: {overall:.1%}" if overall is not None else "Bendras pass rate: n/a")
    for group, rate in summary["group_pass_rates"].items():
        print(f"  {group}: {rate:.1%}" if rate is not None else f"  {group}: n/a")
    print(f"Rezultatas įrašytas: {out_path}")


if __name__ == "__main__":
    main()


__all__ = ["main", "run"]
