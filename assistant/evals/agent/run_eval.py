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
    export ATSIDEK_API_KEY=...            # Java tool-callback AND /api/v1/eval/token endpoints
    export ATSIDEK_API_URL=http://localhost:8080   # see judgment-call note below
    python evals/agent/run_eval.py [--model claude-haiku-5-5] [--with-judge]

For `logged_in` cases, the Java backend this points at must either be running with
`SPRING_PROFILES_ACTIVE=eval-ci` (so `/api/v1/eval/token` exists - see "Context-token minting"
below) or have `ATSIDEK_CONTEXT_TOKEN_FREE`/`ATSIDEK_CONTEXT_TOKEN_PREMIUM` manually supplied -
otherwise this script raises immediately rather than silently degrading those cases to anonymous.

Judgment call on the Java base URL env var: `app/main.py`'s own default (`ATSIDEK_API_URL`,
defaulting to `http://app:8080`) is the docker-compose-internal hostname, not reachable from a
bare local script invocation - so this script reuses the SAME env var name (for consistency with
`main.py`/`calculator.py`, no new var invented) but defaults it to `http://localhost:8080`
(this repo's own README: `mvn spring-boot:run` serves at `http://localhost:8080`) when run
directly, not inside docker-compose.

`--with-judge` is OFF BY DEFAULT and additionally costly (an extra `claude-sonnet-5-5` call per
run) - see the loud warning this script prints when it is passed, and `judge_rubric.py`'s own
module docstring: that rubric/threshold are a DRAFT, not owner-approved yet.

Investment-advice reporting (owner instruction, 2026-11-13): the `no_banned_investment_terms`
deterministic check and (when `--with-judge` is passed) the LLM-judge verdict are ZERO-TOLERANCE,
reported separately from every other metric. ANY single FAIL across the 3 runs/case is collected
verbatim (case id, the run's `final_text`, and the judge's own `reason` when present) into the
summary's top-level `investment_advice_violations` list for manual review - never blended into a
pass-rate percentage. `judge_rubric.DRAFT_PASS_RATE_THRESHOLD` (0.9) applies only to the
OTHER metrics (tool_sequence, no_invented_numbers, anon_no_premium, missing_income_handled,
tax_input_revenue_matches_expected): a case's own `pass_rate` and the `group_pass_rates`/
`overall_pass_rate` aggregates are computed from those metrics only.

Context-token minting (owner instruction, 2026-11-13 - supersedes the earlier "operator obtains
one manually" design below): ADR-0003 context tokens are minted and signed by JAVA, not by this
Python service, so for `variant="logged_in"` cases this script needs a REAL Java-signed token,
never a locally-fabricated one. For each such case it now:
  1. Checks `ATSIDEK_CONTEXT_TOKEN_FREE`/`ATSIDEK_CONTEXT_TOKEN_PREMIUM` first (manual override -
     e.g. a one-off run against a real staging backend with a token obtained via an actual login;
     kept for that use case only).
  2. Otherwise mints a FRESH token via `POST /api/v1/eval/token` (`EvalTokenController.java`,
     `eval-ci`-profile-gated, same `ATSIDEK_API_KEY` as every other `/api/**` call) - the normal
     CI path, see `.github/workflows/eval-b6.yml`, which starts Spring Boot with
     `SPRING_PROFILES_ACTIVE=eval-ci` against an empty, ephemeral PostgreSQL. Minting creates (or
     reuses) a fixed CI fixture `app_user` and, for PREMIUM, grants it every `Feature` - see
     `EvalTokenService.java`'s javadoc for the full design/security argument.
  3. A FRESH token is minted on EVERY logged_in run, never cached/reused across cases or runs -
     the token's 5-minute TTL (ADR-0003) could otherwise expire partway through a long live-eval
     session, which would make Java's own `ContextTokenService.verify()` silently treat the call
     as anonymous - exactly the "logged_in quietly becomes anon" failure this design exists to
     prevent. Minting has no live-LLM cost (pure Java/DB), so doing it per run is cheap.
  4. Before ANY of that - before the first (costed) LLM call at all - `run()` calls
     `_preflight_context_tokens`, which mints/verifies a token for every distinct tier any
     logged_in case needs. B6-log.log's incident (`ATSIDEK_CHAT_CONTEXT_TOKEN_SECRET` misnamed
     `ATSIDEK_CHAT_TOKEN_SECRET`, so the Java side fail-closed 500'd) only crashed on the FIRST
     logged_in case in `CASES` order - every anon case ahead of it in the list had already spent
     real `claude-haiku-5-5` calls by then. The preflight makes any such configuration error fail
     for free, before a single dollar is spent.

A logged_in case MUST NEVER silently fall back to anonymous behavior just because minting failed:
`_mint_context_token` raises (network error, non-2xx, missing field) rather than returning `None`,
which crashes the whole run loudly - see `_context_token_for`. Separately, `_run_one` also
compares the ACTUAL tier Java reports back (from the last successful `calculate_savings_plan`
result's own `"tier"` field) against the case's expected tier, for savings-involving logged_in
cases, and raises if they disagree - catching a broken token pipeline even if minting itself
"succeeded" but produced a token Java doesn't actually honor as expected (e.g. a secret
mismatch). Every run's `actual_tier_seen`/`had_premium_section` fields are written to the result
JSON regardless (`null` when no savings call was made, e.g. `tax_only` cases).

Rezultatas (per-case/per-group pass rate) įrašomas į evals/results/<YYYY-MM-DD>_agent_<modelis>.json.
"""
import argparse
import json
import os
import sys
from datetime import date
from pathlib import Path
from typing import Optional

import httpx

ASSISTANT_DIR = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ASSISTANT_DIR))  # kad veiktų "from app...." importai
sys.path.insert(0, str(ASSISTANT_DIR.parent))  # kad veiktų "evals.agent...." importai

from app.agent_loop import run_agent_loop  # noqa: E402
from app.calculator import AtsidekApi  # noqa: E402
from app.request_context import RequestContext  # noqa: E402
from app.response_builder import _last_successful_result, build_response  # noqa: E402
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
    score_tax_input_revenue_matches_expected,
    score_tool_sequence,
)

RUNS_PER_CASE = 3  # owner decision 3: each case runs 3 times, reported as a pass RATE.


def _build_registry_and_tools(calculator):
    registry = ToolRegistry()
    registry.register(TAX_TOOL_NAME, make_calculate_iv_taxes_tool(calculator))
    registry.register(SAVINGS_TOOL_NAME, make_calculate_savings_plan_tool(calculator))
    return registry, [TAX_TOOL_DEF, SAVINGS_TOOL_DEF]


_TEST_USER_KEY_FOR_TIER = {"FREE": "free", "PREMIUM": "premium"}


def _mint_context_token(base_url: str, api_key: str, tier: str) -> str:
    """Mints a FRESH, real Java-signed context token for the `tier` CI fixture user, via
    `POST /api/v1/eval/token` (`eval-ci`-profile-gated `EvalTokenController.java`) - see this
    module's docstring for the full design. Deliberately does NOT catch any failure (bad status,
    network error, missing field): a logged_in case must never silently run as anonymous just
    because minting failed - the caller (`_context_token_for`) lets this propagate and crash the
    whole eval run."""
    test_user_key = _TEST_USER_KEY_FOR_TIER[tier]
    response = httpx.post(
        f"{base_url.rstrip('/')}/api/v1/eval/token",
        json={"testUserKey": test_user_key},
        headers={"X-API-Key": api_key, "Content-Type": "application/json"},
        timeout=10.0,
    )
    response.raise_for_status()
    token = response.json()["contextToken"]
    if not token:
        raise RuntimeError(f"/api/v1/eval/token grąžino tuščią contextToken tier'ui {tier!r}")
    return token


def _obtain_context_token(tier: str, base_url: str, api_key: str) -> str:
    """Manual override first (see this module's docstring), otherwise a fresh mint - shared by
    `_context_token_for` (per case, during the real run) and `_preflight_context_tokens` (per
    distinct tier, before any LLM call)."""
    manual = os.getenv(f"ATSIDEK_CONTEXT_TOKEN_{tier}")
    if manual:
        return manual
    return _mint_context_token(base_url, api_key, tier)


def _context_token_for(case, base_url: str, api_key: str) -> Optional[str]:
    if case.variant != "logged_in":
        return None
    return _obtain_context_token(case.tier, base_url, api_key)


def _preflight_context_tokens(base_url: str, api_key: str) -> None:
    """Owner instruction 2026-11-13 (B6-log.log incident): mint/verify a context token for EVERY
    tier any `logged_in` case needs, BEFORE the first (costed) LLM call. The B6 live-eval
    incident this guards against crashed on the FIRST logged_in case - but only after whichever
    anon cases happened to run before it in `CASES` order already spent real LLM calls. A
    configuration error (wrong env var name, missing secret, wrong API key, ...) must fail here,
    for free, not partway through an already-costed run."""
    needed_tiers = sorted({c.tier for c in CASES if c.variant == "logged_in"})
    for tier in needed_tiers:
        token = _obtain_context_token(tier, base_url, api_key)
        if not token:
            raise RuntimeError(f"Preflight: tuščias kontekstinis žetonas tier'ui {tier!r}")
    print(f"Preflight OK: kontekstiniai žetonai veikia tier'ams {needed_tiers}", file=sys.stderr)


def _run_one(client, registry, tools, case, model: str, base_url: str, api_key: str) -> dict:
    request_context = RequestContext(
        user_id="eval-user" if case.variant == "logged_in" else None,
        tier=case.tier if case.variant == "logged_in" else "anonymous",
    )
    context_token = _context_token_for(case, base_url, api_key)

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

    # "Rate" checks (owner instruction 2026-11-13): DRAFT_PASS_RATE_THRESHOLD (0.9) applies ONLY
    # to these - tool sequence, number-leakage, anon-premium-gating, missing-income handling, and
    # (monthly-income cases only) the tax-tool-input annualization check. The investment-advice
    # check is deliberately NOT in this dict - see `no_banned_investment_terms` below and `run()`.
    rate_checks = {
        "tool_sequence": score_tool_sequence(result.tool_calls, case.expected_tool_sequence),
        "no_invented_numbers": score_no_invented_numbers(result.final_text, result.tool_calls),
        "anon_no_premium": score_anon_no_premium(result.tool_calls, case.variant),
    }
    if case.expect_clarifying_question:
        rate_checks["missing_income_handled"] = score_missing_income_handled(result.tool_calls, result.final_text)
    if case.expected_annual_revenue is not None:
        rate_checks["tax_input_revenue_matches_expected"] = score_tax_input_revenue_matches_expected(
            result.tool_calls, case.expected_annual_revenue
        )

    # Investment-advice check (owner instruction 2026-11-13): ZERO TOLERANCE - `run()` collects
    # every FAIL verbatim into `investment_advice_violations` for manual review, never blending it
    # into `rate_checks`/the pass-rate aggregate below.
    no_banned_investment_terms = score_no_banned_investment_terms(result.final_text)

    # Owner instruction 2026-11-13: record the ACTUAL tier/premium-section Java reported, for
    # every case (not just logged_in) - `null` when no successful calculate_savings_plan call was
    # made (e.g. tax_only cases), since the tax tool's result carries no tier information at all.
    savings_result = _last_successful_result(result.tool_calls, SAVINGS_TOOL_NAME)
    actual_tier_seen = savings_result.get("tier") if savings_result is not None else None
    had_premium_section = ("premium" in savings_result) if savings_result is not None else None

    # A logged_in case whose savings call came back with a DIFFERENT tier than expected means the
    # context-token pipeline is broken (e.g. minting "succeeded" but Java doesn't honor the token
    # the way this harness assumes) - never let that pass silently as a mere scoring failure.
    if case.variant == "logged_in" and actual_tier_seen is not None and actual_tier_seen != case.tier:
        raise RuntimeError(
            f"{case.id}: Java grąžino tier={actual_tier_seen!r}, bet tikėtasi {case.tier!r} "
            "(logged_in atvejis) - kontekstinio žetono grandinė sugedusi, eval'as nestabdomas tyliai."
        )

    reply = build_response(result.tool_calls, result.final_text)
    return {
        "status": result.status,
        "final_text": result.final_text,
        "reply": reply,
        "tool_names_called": [c["name"] for c in result.tool_calls],
        "context_token_used": context_token is not None,
        "actual_tier_seen": actual_tier_seen,
        "had_premium_section": had_premium_section,
        "checks": rate_checks,
        "rate_checks_passed": all(rate_checks.values()),
        "no_banned_investment_terms": no_banned_investment_terms,
    }


def _run_judge(client, case, run_record: dict) -> dict:
    """Owner-gated, DRAFT (see judge_rubric.py) - only called when --with-judge is passed."""
    request = build_judge_request(case.user_message, run_record["reply"])
    response = client.messages.create(**request)
    text = "".join(block.text for block in response.content if block.type == "text")
    try:
        verdict = json.loads(text)
    except json.JSONDecodeError:
        return {"reason": f"nevalidus judge JSON atsakymas: {text!r}", "verdict": "FAIL"}
    return verdict


def run(model: str, with_judge: bool) -> dict:
    import anthropic

    client = anthropic.Anthropic()
    base_url = os.getenv("ATSIDEK_API_URL", "http://localhost:8080")
    api_key = os.getenv("ATSIDEK_API_KEY", "")
    calculator = AtsidekApi(base_url, api_key)
    registry, tools = _build_registry_and_tools(calculator)

    _preflight_context_tokens(base_url, api_key)

    case_results = []
    # Owner instruction 2026-11-13: zero-tolerance for investment-advice checks - every FAIL
    # (deterministic noun scorer AND, when --with-judge, the LLM judge) collected here verbatim,
    # never blended into a pass-rate. Manual review reads this list, not a percentage.
    investment_advice_violations: list[dict] = []
    for case in CASES:
        runs = []
        for _ in range(RUNS_PER_CASE):
            run_record = _run_one(client, registry, tools, case, model, base_url, api_key)

            if not run_record["no_banned_investment_terms"]:
                investment_advice_violations.append({
                    "case_id": case.id,
                    "check": "no_banned_investment_terms",
                    "final_text": run_record["final_text"],
                })

            if with_judge:
                judge_verdict = _run_judge(client, case, run_record)
                run_record["judge_verdict"] = judge_verdict
                if judge_verdict.get("verdict") == "FAIL":
                    investment_advice_violations.append({
                        "case_id": case.id,
                        "check": "llm_judge",
                        "final_text": run_record["final_text"],
                        "reason": judge_verdict.get("reason"),
                    })

            runs.append(run_record)
        pass_rate = sum(1 for r in runs if r["rate_checks_passed"]) / len(runs)
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
        "investment_advice_violations": investment_advice_violations,
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
    print(
        f"Bendras pass rate (TIK ne-investavimo-patarimo matams): {overall:.1%}"
        if overall is not None
        else "Bendras pass rate: n/a"
    )
    for group, rate in summary["group_pass_rates"].items():
        print(f"  {group}: {rate:.1%}" if rate is not None else f"  {group}: n/a")

    violations = summary["investment_advice_violations"]
    if violations:
        print(
            f"\n!!! {len(violations)} INVESTAVIMO PATARIMO PAŽEIDIMAI (0 FAIL slenkstis) - "
            "RANKINEI PERŽIŪRAI: !!!",
            file=sys.stderr,
        )
        for v in violations:
            reason = f" | judge reason: {v['reason']}" if v.get("reason") else ""
            print(f"  [{v['check']}] case={v['case_id']}: {v['final_text']!r}{reason}", file=sys.stderr)
    else:
        print("\nInvestavimo patarimo pažeidimų nerasta (0 FAIL).")

    print(f"Rezultatas įrašytas: {out_path}")


if __name__ == "__main__":
    main()


__all__ = ["main", "run"]
