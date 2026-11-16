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
    python evals/agent/run_eval.py [--model claude-haiku-5-5] [--with-judge] [--concurrency N]

    # Re-score an EXISTING results file with the current deterministic scorers, no LLM/Java calls,
    # zero cost (e.g. to check a scoring.py fix against a past real run):
    python evals/agent/run_eval.py --rescore evals/results/2026-11-16_agent_claude-haiku-5-5.json

`--concurrency N` (owner instruction, item 9) runs N (case, run-index) units in parallel via a
ThreadPoolExecutor instead of one at a time - a fully sequential run took ~17.5 minutes.

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
run) - see the loud warning this script prints when it is passed. The rubric/threshold themselves
are owner-APPROVED (2026-11-16, after B6 eval #11 - see `judge_rubric.py`'s own module docstring);
`--with-judge` stays opt-in purely because of its extra per-run cost, not because of any remaining
draft status.

Owner instruction (2026-11-16): `judge_rubric.JUDGE_RUBRIC_VERSION` is stamped onto every
`judge_verdict` AND the summary's top-level `judge_rubric_version` field, so a results JSON always
shows which rubric wording actually produced it - bump that constant whenever the rubric prompt
text changes.

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

Rezultatas (per-case/per-group pass rate) įrašomas į evals/results/<YYYY-MM-DD>_agent_<modelis>.json
- data VISADA tikra (realaus paleidimo diena; --rescore atveju paimama iš PERSKAITYTO failo paties
"date" lauko, NE šios dienos, kad failo vardas tyliai nemeluotų apie naują gyvą paleidimą), su
"_rescored" priesaga, kai naudotas --rescore.
"""
import argparse
import json
import os
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date
from decimal import Decimal
from pathlib import Path
from typing import Any, Callable, Optional

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
from evals.agent.judge_rubric import JUDGE_RUBRIC_VERSION, build_judge_request  # noqa: E402
from evals.agent.scoring import (  # noqa: E402
    score_anon_no_premium,
    score_missing_amount_handled,
    score_missing_income_handled,
    score_no_banned_investment_terms,
    score_no_invented_numbers,
    score_response_complete,
    score_tax_input_revenue_matches_expected,
    score_tool_sequence,
)

RUNS_PER_CASE = 3  # owner decision 3: each case runs 3 times, reported as a pass RATE.


def _git_info() -> dict[str, Optional[str]]:
    """Owner instruction (second B6 live-eval round): record exactly which code a run tested, so
    the results JSON is self-describing without cross-referencing the Actions run log. Prefers
    `GITHUB_SHA`/`GITHUB_REF_NAME` (set by GitHub Actions for every job) - falls back to a local
    `git rev-parse` for a manual/local invocation, where those env vars are unset. Never raises:
    a git lookup failing (not a repo, no git binary) must not crash the whole eval run over a
    metadata field."""
    sha = os.getenv("GITHUB_SHA")
    branch = os.getenv("GITHUB_REF_NAME")
    if not sha:
        try:
            sha = subprocess.run(
                ["git", "rev-parse", "HEAD"], cwd=ASSISTANT_DIR,
                capture_output=True, text=True, check=True, timeout=5,
            ).stdout.strip()
        except Exception:
            sha = None
    if not branch:
        try:
            branch = subprocess.run(
                ["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=ASSISTANT_DIR,
                capture_output=True, text=True, check=True, timeout=5,
            ).stdout.strip()
        except Exception:
            branch = None
    return {"git_sha": sha, "git_branch": branch}


def _json_default(o: Any):
    """`AtsidekApi` parses every Java JSON response with `parse_float=Decimal` (see
    `app/calculator.py`), so a stored run's `tool_calls` (item 9: now kept in full, for
    `--rescore`) carries real `Decimal` values - not JSON-serializable by default. Encodes as a
    string (not float), matching `calculator._json_default_decimal_as_str`'s own rationale: avoid
    float rounding error round-tripping through JSON."""
    if isinstance(o, Decimal):
        return str(o)
    raise TypeError(f"Object of type {type(o)} is not JSON serializable")


def _write_results(summary: dict, out_path: Path) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2, default=_json_default), encoding="utf-8"
    )


def _execute_work_items(work_items: list, worker: Callable[[Any], Any], concurrency: int) -> dict:
    """Runs `worker(item)` for each item in `work_items`, sequentially when `concurrency <= 1`
    (the historical, default behaviour - cases execute in `CASES` order, one LLM call at a time)
    or via a `ThreadPoolExecutor` when `concurrency > 1` (owner instruction, item 9: a live run
    took ~17.5 minutes; each (case, run index) pair is an independent LLM/Java round-trip, so
    running several concurrently cuts wall-clock time). Returns `{item: worker(item)}` for every
    item - an exception from any one worker propagates immediately (never swallowed), matching
    this module's existing "never let a broken case pass silently" stance (see `_run_one`'s own
    tier-mismatch check).
    """
    if concurrency <= 1:
        return {item: worker(item) for item in work_items}
    results: dict = {}
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        future_to_item = {pool.submit(worker, item): item for item in work_items}
        for future in as_completed(future_to_item):
            results[future_to_item[future]] = future.result()
    return results


def _aggregate_case_results(case_results: list[dict]) -> dict:
    """Shared by `run()` and `--rescore` (`_rescore`): turns a list of per-case result dicts (each
    with `group`/`pass_rate`/`check_pass_rates`) into `group_pass_rates`/`overall_check_pass_rates`/
    `overall_pass_rate` - the exact same aggregation either way, so a rescored file's summary stats
    are computed identically to a live run's, never a separate/drifting implementation."""
    groups = {cr["group"] for cr in case_results}
    all_check_names = {name for cr in case_results for name in cr["check_pass_rates"]}

    def _per_check_rates(cases_subset: list[dict]) -> dict:
        per_check = {}
        for name in all_check_names:
            rates = [cr["check_pass_rates"][name] for cr in cases_subset if name in cr["check_pass_rates"]]
            per_check[name] = sum(rates) / len(rates) if rates else None
        return per_check

    group_pass_rates = {
        group: _per_check_rates([cr for cr in case_results if cr["group"] == group]) for group in groups
    }
    return {
        "group_pass_rates": group_pass_rates,
        "overall_check_pass_rates": _per_check_rates(case_results),
        "overall_pass_rate": (
            sum(cr["pass_rate"] for cr in case_results) / len(case_results) if case_results else None
        ),
    }


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
        "no_invented_numbers": score_no_invented_numbers(
            result.final_text, result.tool_calls, case.user_message
        ),
        "anon_no_premium": score_anon_no_premium(result.tool_calls, case.variant),
        "response_complete": score_response_complete(result.stop_reason),
    }
    if case.expect_clarifying_question:
        rate_checks["missing_income_handled"] = score_missing_income_handled(
            result.tool_calls, result.final_text, case.user_message
        )
    if case.expect_amount_clarifying_question:
        rate_checks["missing_amount_handled"] = score_missing_amount_handled(
            result.tool_calls, result.final_text, case.user_message
        )
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
        # Owner instruction (2026-11-16, item 9): the FULL tool_calls (not just names) are kept
        # too, so `--rescore` can re-run every deterministic scoring function against a past run
        # without re-calling the LLM/Java backend - `tool_names_called` alone wasn't enough to
        # reconstruct e.g. score_no_invented_numbers' rendered-number comparison or
        # score_anon_no_premium's "premium" key check.
        "tool_calls": result.tool_calls,
        # Owner instruction (2026-11-16, item 3): stored too (not just folded into `checks`
        # below), so `--rescore` can recompute `response_complete` from a past run without a new
        # live LLM call - mirrors why `tool_calls` itself is stored in full (item 9).
        "stop_reason": result.stop_reason,
        "context_token_used": context_token is not None,
        "actual_tier_seen": actual_tier_seen,
        "had_premium_section": had_premium_section,
        "checks": rate_checks,
        "rate_checks_passed": all(rate_checks.values()),
        "no_banned_investment_terms": no_banned_investment_terms,
    }


_VALID_JUDGE_VERDICTS = ("PASS", "FAIL")


def _run_judge(client, case, run_record: dict) -> dict:
    """Owner-approved rubric (see judge_rubric.py) - only called when --with-judge is passed
    (opt-in purely for its extra per-run cost, not because of any remaining draft status).

    Owner instruction (second B6 live-eval round): a judge TECHNICAL failure (empty/truncated/
    invalid JSON, or valid JSON missing a recognized "verdict") is a distinct {"verdict": "ERROR"}
    - it must NEVER be reported as {"verdict": "FAIL"} (a real investment-advice violation) and
    must NEVER land in `investment_advice_violations` (that list is for actual content
    violations, read verbatim for manual review - mixing in infra noise would defeat its point).
    `run()` collects ERRORs separately and marks the whole run as broken (`main()` exits non-zero)
    - a judge technical error means the CHECK didn't run, not that the agent's reply passed.

    Owner instruction (2026-11-16): every returned dict (PASS/FAIL AND each ERROR path) carries
    `rubric_version` (`judge_rubric.JUDGE_RUBRIC_VERSION`), so a results JSON always shows which
    rubric wording actually produced a given verdict - including a technical-error verdict, since
    even that still used (and failed to get a usable answer from) a specific rubric version.
    """
    request = build_judge_request(case.user_message, run_record["reply"])
    response = client.messages.create(**request)
    text = "".join(block.text for block in response.content if block.type == "text")
    if not text.strip():
        return {
            "reason": "judge grąžino tuščią text bloką (žr. max_tokens/thinking)",
            "verdict": "ERROR",
            "rubric_version": JUDGE_RUBRIC_VERSION,
        }
    try:
        verdict = json.loads(text)
    except json.JSONDecodeError:
        return {
            "reason": f"nevalidus judge JSON atsakymas: {text!r}",
            "verdict": "ERROR",
            "rubric_version": JUDGE_RUBRIC_VERSION,
        }
    if not isinstance(verdict, dict) or verdict.get("verdict") not in _VALID_JUDGE_VERDICTS:
        return {
            "reason": f"judge JSON neturi atpažinto 'verdict' lauko: {text!r}",
            "verdict": "ERROR",
            "rubric_version": JUDGE_RUBRIC_VERSION,
        }
    verdict["rubric_version"] = JUDGE_RUBRIC_VERSION
    return verdict


def _execute_case_run(client, registry, tools, case, model: str, base_url: str, api_key: str, with_judge: bool) -> dict:
    """One (case, run) unit of work: `_run_one` plus, when `--with-judge` is passed, the judge
    call for that same run - kept together so `--concurrency` overlaps BOTH the agent call and the
    judge call for a given run, not just the agent call."""
    run_record = _run_one(client, registry, tools, case, model, base_url, api_key)
    if with_judge:
        run_record["judge_verdict"] = _run_judge(client, case, run_record)
    return run_record


def run(model: str, with_judge: bool, concurrency: int = 1) -> dict:
    import anthropic

    client = anthropic.Anthropic()
    base_url = os.getenv("ATSIDEK_API_URL", "http://localhost:8080")
    api_key = os.getenv("ATSIDEK_API_KEY", "")
    calculator = AtsidekApi(base_url, api_key)
    registry, tools = _build_registry_and_tools(calculator)

    _preflight_context_tokens(base_url, api_key)

    # Owner instruction (2026-11-16, item 9): --concurrency overlaps independent (case, run index)
    # units of work via a ThreadPoolExecutor (see `_execute_work_items`) - a live run took ~17.5
    # minutes with everything sequential. Each unit's own LLM/Java calls are unaffected; only the
    # SCHEDULING across units changes. `work_items` fixes a stable, hashable key per unit so
    # results can be looked back up in `CASES` order below regardless of actual completion order.
    #
    # Bug fix (2026-11-16, live CI run `TypeError: unhashable type: 'list'`): the key must be
    # `case.id` (a str), NOT the `EvalCase` instance itself - `EvalCase` is a frozen dataclass, but
    # its auto-generated `__hash__` hashes every field including `expected_tool_sequence` (a
    # `list`), which is itself unhashable. `(case, i)` tuples as dict keys therefore crashed on
    # the FIRST lookup/insertion, in both the sequential and ThreadPoolExecutor paths of
    # `_execute_work_items` - case ids are unique (test_eval_cases.py::test_case_ids_are_unique),
    # so `(case.id, i)` is a safe, stable substitute; `_case_by_id` looks the case back up.
    work_items = [(case.id, i) for case in CASES for i in range(RUNS_PER_CASE)]
    raw_results = _execute_work_items(
        work_items,
        lambda item: _execute_case_run(
            client, registry, tools, _case_by_id(item[0]), model, base_url, api_key, with_judge
        ),
        concurrency,
    )

    case_results = []
    # Owner instruction 2026-11-13: zero-tolerance for investment-advice checks - every FAIL
    # (deterministic noun scorer AND, when --with-judge, the LLM judge) collected here verbatim,
    # never blended into a pass-rate. Manual review reads this list, not a percentage. Collected
    # in CASES order below (not raw completion order) so the list stays deterministic regardless
    # of --concurrency.
    investment_advice_violations: list[dict] = []
    # Owner instruction (second B6 live-eval round): judge TECHNICAL errors (empty/invalid JSON)
    # are collected separately from `investment_advice_violations` - they mean the check didn't
    # run, not that it passed or failed. Any entry here marks the whole run as broken (see
    # `main()`'s exit code).
    judge_errors: list[dict] = []
    for case in CASES:
        runs = [raw_results[(case.id, i)] for i in range(RUNS_PER_CASE)]

        for run_record in runs:
            if not run_record["no_banned_investment_terms"]:
                investment_advice_violations.append({
                    "case_id": case.id,
                    "check": "no_banned_investment_terms",
                    "final_text": run_record["final_text"],
                })
            judge_verdict = run_record.get("judge_verdict")
            if judge_verdict is not None:
                if judge_verdict.get("verdict") == "FAIL":
                    investment_advice_violations.append({
                        "case_id": case.id,
                        "check": "llm_judge",
                        "final_text": run_record["final_text"],
                        "reason": judge_verdict.get("reason"),
                    })
                elif judge_verdict.get("verdict") == "ERROR":
                    judge_errors.append({
                        "case_id": case.id,
                        "final_text": run_record["final_text"],
                        "reason": judge_verdict.get("reason"),
                    })

        pass_rate = sum(1 for r in runs if r["rate_checks_passed"]) / len(runs)
        # Owner instruction (second B6 live-eval round): the blended per-case pass_rate above
        # hides WHICH check is failing (e.g. missing_income_handled going 0/6 was invisible next
        # to tool_sequence/no_invented_numbers passing fine). Compute a pass rate PER CHECK NAME
        # too - a check's presence is constant across a case's RUNS_PER_CASE runs (it depends only
        # on fixed case properties like expect_clarifying_question), never flaky per-run.
        check_names = {name for r in runs for name in r["checks"]}
        check_pass_rates = {
            name: sum(1 for r in runs if r["checks"][name]) / len(runs) for name in check_names
        }
        case_results.append({
            "id": case.id,
            "group": case.group,
            "variant": case.variant,
            "pass_rate": pass_rate,
            "check_pass_rates": check_pass_rates,
            "runs": runs,
        })

    return {
        "model": model,
        "date": date.today().isoformat(),
        **_git_info(),
        "runs_per_case": RUNS_PER_CASE,
        "with_judge": with_judge,
        "judge_rubric_version": JUDGE_RUBRIC_VERSION if with_judge else None,
        "concurrency": concurrency,
        "cases": len(CASES),
        **_aggregate_case_results(case_results),
        "investment_advice_violations": investment_advice_violations,
        "judge_errors": judge_errors,
        "run_broken": bool(judge_errors),
        "case_results": case_results,
    }


def _case_by_id(case_id: str):
    for case in CASES:
        if case.id == case_id:
            return case
    raise KeyError(f"Nežinomas case id {case_id!r} - evals/agent/cases.py nebeturi šio atvejo.")


def rescore(results_path: Path) -> dict:
    """`--rescore <path>`: re-runs every DETERMINISTIC scoring function (never the LLM judge, and
    NEVER calls the live Anthropic/Java backends) against an EXISTING results JSON's own stored
    `tool_calls`/`final_text`, so a scorer code fix (e.g. item 8's missing_income_handled fix) can
    be checked against a past real live run without spending any more LLM cost.

    A stored `judge_verdict` (if the original run used --with-judge) is carried over UNCHANGED -
    rescoring never re-judges, only re-applies the deterministic checks.

    Owner instruction (2026-11-16, item 9): the rescored output keeps the ORIGINAL run's own
    `date`/`git_sha`/`git_branch` (it describes what was actually tested against a live LLM, which
    rescoring does not repeat) and the output filename is built from that SAME original date, not
    today's - stamping today's date on a rescore would misleadingly suggest a fresh live run.
    """
    original = json.loads(results_path.read_text(encoding="utf-8"), parse_float=Decimal)

    case_results = []
    investment_advice_violations: list[dict] = []
    judge_errors: list[dict] = list(original.get("judge_errors", []))
    for case_result in original["case_results"]:
        case = _case_by_id(case_result["id"])
        runs = []
        for stored_run in case_result["runs"]:
            tool_calls = stored_run["tool_calls"]
            final_text = stored_run["final_text"]

            rate_checks = {
                "tool_sequence": score_tool_sequence(tool_calls, case.expected_tool_sequence),
                "no_invented_numbers": score_no_invented_numbers(final_text, tool_calls, case.user_message),
                "anon_no_premium": score_anon_no_premium(tool_calls, case.variant),
                # .get(), not [] - older results files (predating item 3) have no "stop_reason"
                # key at all; defaulting to None passes (score_response_complete(None) is True).
                "response_complete": score_response_complete(stored_run.get("stop_reason")),
            }
            if case.expect_clarifying_question:
                rate_checks["missing_income_handled"] = score_missing_income_handled(
                    tool_calls, final_text, case.user_message
                )
            if case.expect_amount_clarifying_question:
                rate_checks["missing_amount_handled"] = score_missing_amount_handled(
                    tool_calls, final_text, case.user_message
                )
            if case.expected_annual_revenue is not None:
                rate_checks["tax_input_revenue_matches_expected"] = score_tax_input_revenue_matches_expected(
                    tool_calls, case.expected_annual_revenue
                )
            no_banned_investment_terms = score_no_banned_investment_terms(final_text)

            run_record = dict(stored_run)
            run_record["checks"] = rate_checks
            run_record["rate_checks_passed"] = all(rate_checks.values())
            run_record["no_banned_investment_terms"] = no_banned_investment_terms

            if not no_banned_investment_terms:
                investment_advice_violations.append({
                    "case_id": case.id,
                    "check": "no_banned_investment_terms",
                    "final_text": final_text,
                })
            judge_verdict = run_record.get("judge_verdict")
            if judge_verdict is not None and judge_verdict.get("verdict") == "FAIL":
                investment_advice_violations.append({
                    "case_id": case.id,
                    "check": "llm_judge",
                    "final_text": final_text,
                    "reason": judge_verdict.get("reason"),
                })

            runs.append(run_record)

        pass_rate = sum(1 for r in runs if r["rate_checks_passed"]) / len(runs)
        check_names = {name for r in runs for name in r["checks"]}
        check_pass_rates = {
            name: sum(1 for r in runs if r["checks"][name]) / len(runs) for name in check_names
        }
        case_results.append({
            "id": case.id,
            "group": case.group,
            "variant": case_result.get("variant", case.variant),
            "pass_rate": pass_rate,
            "check_pass_rates": check_pass_rates,
            "runs": runs,
        })

    return {
        **{k: v for k, v in original.items() if k not in ("case_results", "investment_advice_violations")},
        **_aggregate_case_results(case_results),
        "investment_advice_violations": investment_advice_violations,
        "judge_errors": judge_errors,
        "run_broken": bool(judge_errors),
        "case_results": case_results,
        "rescored": True,
        "rescored_at": date.today().isoformat(),
        "rescore_source": str(results_path),
    }


def _print_summary(summary: dict, out_path: Path) -> None:
    overall = summary["overall_pass_rate"]
    print(
        f"Bendras pass rate (TIK ne-investavimo-patarimo matams): {overall:.1%}"
        if overall is not None
        else "Bendras pass rate: n/a"
    )
    # Owner instruction (second B6 live-eval round): group_pass_rates is now PER CHECK, so a
    # single failing check (e.g. missing_income_handled) is visible by name, not hidden inside a
    # blended group average.
    for group, check_rates in summary["group_pass_rates"].items():
        print(f"  {group}:")
        for check, rate in check_rates.items():
            print(f"    {check}: {rate:.1%}" if rate is not None else f"    {check}: n/a")

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

    # Owner instruction (second B6 live-eval round): a judge TECHNICAL error (empty/invalid JSON)
    # must mark the whole run as broken, distinct from a real content violation above.
    judge_errors = summary["judge_errors"]
    if judge_errors:
        print(
            f"\n!!! {len(judge_errors)} JUDGE TECHNINĖS KLAIDOS (tuščias/nevalidus JSON, NE "
            "turinio pažeidimas) - PALEIDIMAS SUGEDĘS: !!!",
            file=sys.stderr,
        )
        for e in judge_errors:
            print(f"  case={e['case_id']}: {e['reason']}", file=sys.stderr)

    print(f"Rezultatas įrašytas: {out_path}")

    if summary["run_broken"]:
        sys.exit(2)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", default=os.getenv("AI_MODEL", "claude-haiku-5-5"))
    parser.add_argument(
        "--with-judge",
        action="store_true",
        default=False,
        help=(
            f"LLM judge (claude-sonnet-5-5, patvirtinta rubrika {JUDGE_RUBRIC_VERSION}) - "
            "papildoma kaina; žymėti, kai keičiamas system prompt, agento ciklas ar įrankiai."
        ),
    )
    parser.add_argument(
        "--concurrency",
        type=int,
        default=1,
        help=(
            "How many (case, run) units to execute in parallel (default 1 = sequential, the "
            "historical behaviour). A live run took ~17.5 minutes fully sequential - each unit is "
            "an independent LLM/Java round-trip, so e.g. --concurrency 5 cuts wall-clock time "
            "roughly 5x. Ignored with --rescore (no LLM calls are made at all)."
        ),
    )
    parser.add_argument(
        "--rescore",
        metavar="RESULTS_JSON",
        default=None,
        help=(
            "Re-runs every DETERMINISTIC scoring function against an EXISTING results JSON's own "
            "stored tool_calls/final_text - no live LLM/Java calls, zero cost. Use after a scorer "
            "code fix to check it against a past real run without re-spending money. A stored "
            "judge_verdict (--with-judge runs) is carried over unchanged, never re-judged. "
            "--model/--with-judge/--concurrency are ignored with this flag."
        ),
    )
    args = parser.parse_args()

    if args.rescore:
        results_path = Path(args.rescore)
        if not results_path.is_file():
            print(f"--rescore failas nerastas: {results_path}", file=sys.stderr)
            sys.exit(1)
        summary = rescore(results_path)
        # Real date (item 9): the ORIGINAL run's own date, never today's - a rescore is not a new
        # live run, so stamping today's date on the filename would misleadingly suggest one.
        out_path = ASSISTANT_DIR / "evals" / "results" / f"{summary['date']}_agent_{summary['model']}_rescored.json"
        _write_results(summary, out_path)
        _print_summary(summary, out_path)
        return

    if not os.getenv("ANTHROPIC_API_KEY"):
        print("Reikia ANTHROPIC_API_KEY aplinkos kintamojo tikram eval'ui paleisti.", file=sys.stderr)
        sys.exit(1)

    if args.with_judge:
        print(
            f"LLM judge (claude-sonnet-5-5, patvirtinta rubrika {JUDGE_RUBRIC_VERSION}, žr. "
            "judge_rubric.py, patvirtinta 2026-11-16 po B6 eval #11) - papildoma kaina; "
            "žymėti, kai keičiamas system prompt, agento ciklas ar įrankiai.",
            file=sys.stderr,
        )

    summary = run(args.model, args.with_judge, args.concurrency)
    # Real date (item 9): date.today() at the moment of the actual live run - already correct, but
    # confirmed/kept explicit here since --rescore (above) deliberately does NOT use today's date.
    out_path = ASSISTANT_DIR / "evals" / "results" / f"{summary['date']}_agent_{args.model}.json"
    _write_results(summary, out_path)
    _print_summary(summary, out_path)


if __name__ == "__main__":
    main()


__all__ = ["main", "rescore", "run"]
