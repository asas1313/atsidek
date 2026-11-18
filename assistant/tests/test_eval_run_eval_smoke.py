"""Smoke tests (B6): `evals/agent/run_eval.py` and `evals/agent/judge_rubric.py` must import
cleanly WITHOUT a live Anthropic client/API key or a live Java backend - this is a syntax/wiring
check only, never an execution of the costed live runner itself (that stays manual/
`workflow_dispatch`-only, per owner decision 3).
"""
import importlib
import json
import subprocess
import sys
from datetime import date
from pathlib import Path

ASSISTANT_DIR = Path(__file__).resolve().parents[1]


def test_run_eval_module_imports_without_a_live_client(monkeypatch):
    monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
    # A separate subprocess (not just importlib.reload in-process) so this is a clean test of
    # "python -c import ..." working from scratch, matching the task's own suggested check.
    result = subprocess.run(
        [sys.executable, "-c", "import evals.agent.run_eval"],
        cwd=str(ASSISTANT_DIR),
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stderr


def test_judge_rubric_module_imports_and_exposes_approved_contract():
    """Owner instruction (2026-11-16, after B6 eval #11): the rubric/thresholds are now APPROVED,
    not a draft - the `DRAFT_` prefix on the two constant names is kept as-is (avoids churning
    every import site for a cosmetic rename) but no longer means "unapproved"; the module
    docstring must say so explicitly."""
    judge_rubric = importlib.import_module("evals.agent.judge_rubric")

    assert judge_rubric.JUDGE_MODEL == "claude-sonnet-5-5"
    assert isinstance(judge_rubric.DRAFT_JUDGE_RUBRIC_PROMPT, str)
    assert judge_rubric.DRAFT_JUDGE_RUBRIC_PROMPT.strip()
    assert isinstance(judge_rubric.DRAFT_PASS_RATE_THRESHOLD, float)
    assert "APPROVED" in judge_rubric.__doc__
    assert "owner" in judge_rubric.__doc__.lower()
    assert "eval #11" in judge_rubric.__doc__ or "feef53f" in judge_rubric.__doc__


def test_judge_rubric_exempts_pension_tier_mentions_as_a_tax_parameter_not_investment_advice():
    """Owner instruction (second B6 live-eval round), item 6a: II/III pillar pension-accumulation
    vocabulary must be an explicit exception in the rubric prompt itself, not just the
    deterministic noun scorer's own exclusion list."""
    judge_rubric = importlib.import_module("evals.agent.judge_rubric")

    lowered = judge_rubric.DRAFT_JUDGE_RUBRIC_PROMPT.lower()
    assert "pakopos" in lowered or "pakopa" in lowered
    assert "pension_accumulation" in judge_rubric.DRAFT_JUDGE_RUBRIC_PROMPT or "mokesč" in lowered

    pension_examples = [ex for ex in judge_rubric.ILLUSTRATIVE_EXAMPLES if "pakop" in ex["text"].lower()]
    assert pension_examples
    assert all(ex["expected_verdict"] == "PASS" for ex in pension_examples)


def test_judge_rubric_v2_allows_a_generic_bank_mention_in_a_pure_refusal():
    """Owner instruction (2026-11-17, item 1, after the first full --via-endpoint eval run
    1c25e9c): a generic category/concept word (bank, savings/financial product, investing) with
    NO concrete name must be allowed inside a REFUSAL - the 2026-11-13 stricter flip had banned
    even the bare word, not just a concrete named instance, and the judge FAILed a correct refusal
    for case adversarial_anon_03 as a result. JUDGE_RUBRIC_VERSION bumped to v2."""
    judge_rubric = importlib.import_module("evals.agent.judge_rubric")

    assert judge_rubric.JUDGE_RUBRIC_VERSION == "v2"
    assert "v2" in judge_rubric.DRAFT_JUDGE_RUBRIC_PROMPT.lower()

    refusal_examples = [
        ex for ex in judge_rubric.ILLUSTRATIVE_EXAMPLES
        if "adversarial_anon_03" in ex.get("note", "") or "v2 exception" in ex.get("note", "")
    ]
    assert refusal_examples
    pass_examples = [ex for ex in refusal_examples if ex["expected_verdict"] == "PASS"]
    fail_examples = [ex for ex in refusal_examples if ex["expected_verdict"] == "FAIL"]
    assert pass_examples
    # The exact run-1c25e9c refusal text (system_prompt.py's fixed sentence) must be in the PASS set.
    assert any(
        "Investavimo patarimų teikti negaliu" in ex["text"] for ex in pass_examples
    )
    # A category mentioned AS A RECOMMENDATION (not a refusal) must still FAIL under v2 - the
    # new exception must not accidentally swallow the 2026-11-13 rule.
    assert fail_examples
    assert any("siūlau" in ex["text"].lower() for ex in fail_examples)


def test_build_judge_request_returns_a_messages_create_shaped_dict_without_calling_anthropic():
    judge_rubric = importlib.import_module("evals.agent.judge_rubric")

    request = judge_rubric.build_judge_request("vartotojo klausimas", "agento atsakymas")

    assert request["model"] == judge_rubric.JUDGE_MODEL
    assert request["system"] == judge_rubric.DRAFT_JUDGE_RUBRIC_PROMPT
    assert isinstance(request["messages"], list) and len(request["messages"]) == 1
    assert "vartotojo klausimas" in request["messages"][0]["content"]
    assert "agento atsakymas" in request["messages"][0]["content"]


def test_build_judge_request_declares_the_report_verdict_tool_with_auto_not_forced_choice():
    """Owner instruction (2026-11-17, item 3): the judge's verdict comes back via a tool_use
    block, but tool_choice is "auto" (NOT a literally forced {"type": "tool", ...}) - that 400s on
    claude-sonnet-5-5 (see judge_rubric.JUDGE_TOOL_SCHEMA's own comment)."""
    judge_rubric = importlib.import_module("evals.agent.judge_rubric")

    request = judge_rubric.build_judge_request("vartotojo klausimas", "agento atsakymas")

    assert request["tool_choice"] == {"type": "auto"}
    [tool] = request["tools"]
    assert tool["name"] == judge_rubric.JUDGE_TOOL_NAME == "report_verdict"
    assert tool["strict"] is True
    schema = tool["input_schema"]
    assert set(schema["required"]) == {"reason", "verdict"}
    assert schema["properties"]["verdict"]["enum"] == ["PASS", "FAIL"]
    assert schema["additionalProperties"] is False


def test_runs_per_case_is_three():
    """Owner instruction 2026-11-13, point 4: confirm each case is run exactly 3 times (owner
    decision 3 - reported as a pass RATE, not a single pass/fail)."""
    run_eval = importlib.import_module("evals.agent.run_eval")

    assert run_eval.RUNS_PER_CASE == 3


def test_cases_module_declares_exactly_the_expected_tool_name_strings():
    """Not tautological: hardcodes the real tool names independently of any implementation
    import, then checks cases.py's CASES only ever reference those two."""
    from evals.agent.cases import CASES

    known_tool_names = {"calculate_iv_taxes", "calculate_savings_plan"}
    for case in CASES:
        assert set(case.expected_tool_sequence) <= known_tool_names, case.id


# --- Context-token minting (owner instruction, 2026-11-13, point 2) --------------------------------
#
# These exercise _context_token_for/_mint_context_token's LOGIC only - no real network call, no
# real Java backend. `httpx.post` is monkeypatched module-wide in `run_eval`.

class _FakeResponse:
    def __init__(self, status_code: int, json_body: dict):
        self.status_code = status_code
        self._json_body = json_body

    def raise_for_status(self):
        if self.status_code >= 400:
            import httpx
            raise httpx.HTTPStatusError("boom", request=None, response=self)

    def json(self):
        return self._json_body


def _anon_case():
    from evals.agent.cases import EvalCase
    return EvalCase(id="x", group="tax_only", variant="anon", tier=None, user_message="m",
                     expected_tool_sequence=["calculate_iv_taxes"])


def _logged_in_case(tier):
    from evals.agent.cases import EvalCase
    return EvalCase(id="x", group="tax_only", variant="logged_in", tier=tier, user_message="m",
                     expected_tool_sequence=["calculate_iv_taxes"])


def test_context_token_for_returns_none_for_anon_without_calling_mint(monkeypatch):
    run_eval = importlib.import_module("evals.agent.run_eval")
    monkeypatch.setattr(run_eval.httpx, "post", lambda *a, **k: (_ for _ in ()).throw(AssertionError("must not call")))

    assert run_eval._context_token_for(_anon_case(), "http://x", "key") is None


def test_context_token_for_uses_manual_env_override_without_calling_mint(monkeypatch):
    run_eval = importlib.import_module("evals.agent.run_eval")
    monkeypatch.setenv("ATSIDEK_CONTEXT_TOKEN_FREE", "manually-obtained-token")
    monkeypatch.setattr(run_eval.httpx, "post", lambda *a, **k: (_ for _ in ()).throw(AssertionError("must not call")))

    token = run_eval._context_token_for(_logged_in_case("FREE"), "http://x", "key")
    assert token == "manually-obtained-token"


def test_context_token_for_mints_via_the_endpoint_when_no_manual_override(monkeypatch):
    run_eval = importlib.import_module("evals.agent.run_eval")
    monkeypatch.delenv("ATSIDEK_CONTEXT_TOKEN_PREMIUM", raising=False)
    calls = []

    def fake_post(url, json, headers, timeout):
        calls.append((url, json, headers))
        return _FakeResponse(200, {"contextToken": "minted-token", "derivedTier": "PREMIUM"})

    monkeypatch.setattr(run_eval.httpx, "post", fake_post)

    token = run_eval._context_token_for(_logged_in_case("PREMIUM"), "http://x:8080/", "the-key")

    assert token == "minted-token"
    assert len(calls) == 1
    url, body, headers = calls[0]
    assert url == "http://x:8080/api/v1/eval/token"
    assert body == {"testUserKey": "premium"}
    assert headers["X-API-Key"] == "the-key"


def test_mint_context_token_raises_on_non_2xx_instead_of_returning_none(monkeypatch):
    run_eval = importlib.import_module("evals.agent.run_eval")
    monkeypatch.setattr(run_eval.httpx, "post", lambda *a, **k: _FakeResponse(404, {}))

    import pytest
    with pytest.raises(Exception):
        run_eval._mint_context_token("http://x", "key", "FREE")


def test_mint_context_token_raises_on_empty_token_in_response(monkeypatch):
    run_eval = importlib.import_module("evals.agent.run_eval")
    monkeypatch.setattr(run_eval.httpx, "post", lambda *a, **k: _FakeResponse(200, {"contextToken": ""}))

    import pytest
    with pytest.raises(RuntimeError):
        run_eval._mint_context_token("http://x", "key", "FREE")


# --- Preflight context-token check (owner instruction, B6-log.log incident, point 3) ----------
# The B6 live-eval incident crashed on the FIRST logged_in case - but only after every anon case
# ahead of it in CASES order had already spent real LLM calls. The preflight must mint/verify a
# token for every distinct tier BEFORE any of that, so a config error (like the incident's wrong
# env var name) is free to detect.

def test_preflight_mints_a_token_for_every_distinct_tier_used_by_logged_in_cases(monkeypatch):
    run_eval = importlib.import_module("evals.agent.run_eval")
    monkeypatch.delenv("ATSIDEK_CONTEXT_TOKEN_FREE", raising=False)
    monkeypatch.delenv("ATSIDEK_CONTEXT_TOKEN_PREMIUM", raising=False)
    minted_for = []

    def fake_post(url, json, headers, timeout):
        minted_for.append(json["testUserKey"])
        return _FakeResponse(200, {"contextToken": f"token-{json['testUserKey']}"})

    monkeypatch.setattr(run_eval.httpx, "post", fake_post)

    run_eval._preflight_context_tokens("http://x", "key")

    # Real CASES use both FREE and PREMIUM logged_in cases - both must have been minted exactly
    # once each (not once per case - a distinct-tier preflight, not a per-case one).
    assert sorted(minted_for) == ["free", "premium"]


def test_preflight_raises_immediately_if_minting_fails_for_any_tier(monkeypatch):
    run_eval = importlib.import_module("evals.agent.run_eval")
    monkeypatch.delenv("ATSIDEK_CONTEXT_TOKEN_FREE", raising=False)
    monkeypatch.delenv("ATSIDEK_CONTEXT_TOKEN_PREMIUM", raising=False)
    monkeypatch.setattr(run_eval.httpx, "post", lambda *a, **k: _FakeResponse(500, {}))

    import pytest
    with pytest.raises(Exception):
        run_eval._preflight_context_tokens("http://x", "key")


def test_preflight_respects_manual_override_without_minting_that_tier(monkeypatch):
    run_eval = importlib.import_module("evals.agent.run_eval")
    monkeypatch.setenv("ATSIDEK_CONTEXT_TOKEN_FREE", "manual-free-token")
    monkeypatch.delenv("ATSIDEK_CONTEXT_TOKEN_PREMIUM", raising=False)
    minted_for = []

    def fake_post(url, json, headers, timeout):
        minted_for.append(json["testUserKey"])
        return _FakeResponse(200, {"contextToken": f"token-{json['testUserKey']}"})

    monkeypatch.setattr(run_eval.httpx, "post", fake_post)

    run_eval._preflight_context_tokens("http://x", "key")

    assert minted_for == ["premium"]


# --- Judge ERROR vs FAIL vs PASS (owner instruction, second B6 live-eval round) ----------------
#
# The second live run showed the judge returning empty/truncated JSON being misreported as a
# content FAIL. `_run_judge` must distinguish a judge TECHNICAL failure ({"verdict": "ERROR"})
# from a real content verdict (PASS/FAIL) - these exercise that distinction with a fake Anthropic
# client, no real API call.
#
# Item 3 (owner instruction, 2026-11-17, after eval 1c25e9c): the judge's verdict now comes from a
# `report_verdict` tool_use block's already-parsed `input` dict, not free JSON text pulled out of
# a text block (a stray quote inside the judge's own "reason" string broke the old `json.loads`
# approach for case adversarial_logged_in_02) - these fakes model a tool_use block instead of a
# text block accordingly.

class _FakeTextBlock:
    def __init__(self, text):
        self.type = "text"
        self.text = text


class _FakeThinkingBlock:
    """Adaptive thinking (owner instruction note): a thinking block must never be read as the
    judge's answer - only a `report_verdict` tool_use block is."""
    def __init__(self):
        self.type = "thinking"
        self.text = "should not be read"


class _FakeToolUseBlock:
    def __init__(self, name, input):
        self.type = "tool_use"
        self.name = name
        self.input = input


class _FakeJudgeResponse:
    def __init__(self, content):
        self.content = content


class _FakeJudgeClient:
    def __init__(self, response):
        self._response = response
        self.messages = self

    def create(self, **kwargs):
        return self._response


def _judge_case():
    from evals.agent.cases import EvalCase
    return EvalCase(id="judge-case", group="tax_only", variant="anon", tier=None,
                     user_message="m", expected_tool_sequence=["calculate_iv_taxes"])


def _fake_verdict_tool_use(**input):
    from evals.agent.judge_rubric import JUDGE_TOOL_NAME
    return _FakeToolUseBlock(JUDGE_TOOL_NAME, input)


def test_run_judge_returns_error_not_fail_when_no_tool_use_block_is_present():
    """The model answered with free text instead of calling report_verdict - a technical ERROR,
    not a real content verdict (mirrors the old empty/invalid-JSON ERROR path)."""
    run_eval = importlib.import_module("evals.agent.run_eval")
    client = _FakeJudgeClient(_FakeJudgeResponse([_FakeTextBlock("laisvas tekstas, ne tool_use")]))

    verdict = run_eval._run_judge(client, _judge_case(), {"reply": "agento atsakymas"})

    assert verdict["verdict"] == "ERROR"
    assert "reason" in verdict


def test_run_judge_returns_error_not_fail_when_response_content_is_entirely_empty():
    """Same ERROR path, no content blocks at all (not even a text one) - tool_choice is "auto",
    so an empty/no-tool_use response is a real possibility this must not crash on."""
    run_eval = importlib.import_module("evals.agent.run_eval")
    client = _FakeJudgeClient(_FakeJudgeResponse([]))

    verdict = run_eval._run_judge(client, _judge_case(), {"reply": "agento atsakymas"})

    assert verdict["verdict"] == "ERROR"
    assert "reason" in verdict


def test_run_judge_returns_error_not_fail_on_a_tool_use_block_with_an_unrecognized_verdict():
    run_eval = importlib.import_module("evals.agent.run_eval")
    client = _FakeJudgeClient(_FakeJudgeResponse(
        [_fake_verdict_tool_use(reason="x", verdict="MAYBE")]
    ))

    verdict = run_eval._run_judge(client, _judge_case(), {"reply": "agento atsakymas"})

    assert verdict["verdict"] == "ERROR"


def test_run_judge_ignores_thinking_blocks_and_reads_the_tool_use_block():
    run_eval = importlib.import_module("evals.agent.run_eval")
    client = _FakeJudgeClient(_FakeJudgeResponse(
        [_FakeThinkingBlock(), _fake_verdict_tool_use(reason="ok", verdict="PASS")]
    ))

    verdict = run_eval._run_judge(client, _judge_case(), {"reply": "agento atsakymas"})

    assert verdict["verdict"] == "PASS"


def test_run_judge_ignores_a_tool_use_block_from_a_different_tool_name():
    """Owner instruction (2026-11-17, item 3): only a block named report_verdict counts - a
    tool_use block from some other (hypothetical) tool must not be misread as the verdict."""
    run_eval = importlib.import_module("evals.agent.run_eval")
    client = _FakeJudgeClient(_FakeJudgeResponse(
        [_FakeToolUseBlock("some_other_tool", {"reason": "ok", "verdict": "PASS"})]
    ))

    verdict = run_eval._run_judge(client, _judge_case(), {"reply": "agento atsakymas"})

    assert verdict["verdict"] == "ERROR"


def test_run_judge_passes_through_a_real_fail_verdict_otherwise_unchanged():
    run_eval = importlib.import_module("evals.agent.run_eval")
    client = _FakeJudgeClient(_FakeJudgeResponse(
        [_fake_verdict_tool_use(reason="paminėjo konkretų fondą", verdict="FAIL")]
    ))

    verdict = run_eval._run_judge(client, _judge_case(), {"reply": "agento atsakymas"})

    assert verdict["verdict"] == "FAIL"
    assert verdict["reason"] == "paminėjo konkretų fondą"


def test_run_judge_survives_a_quote_inside_the_reason_field():
    """The exact bug class item 3 fixes (case adversarial_logged_in_02): a quote character inside
    the judge's own "reason" text used to break json.loads on free JSON text - tool_use.input is
    Anthropic's own already-parsed dict, so this can no longer happen."""
    run_eval = importlib.import_module("evals.agent.run_eval")
    reason_with_a_quote = 'paminėjo "konkretų" fondą citatose'
    client = _FakeJudgeClient(_FakeJudgeResponse(
        [_fake_verdict_tool_use(reason=reason_with_a_quote, verdict="FAIL")]
    ))

    verdict = run_eval._run_judge(client, _judge_case(), {"reply": "agento atsakymas"})

    assert verdict["verdict"] == "FAIL"
    assert verdict["reason"] == reason_with_a_quote


# Owner instruction (2026-11-16): the rubric version must be stamped onto EVERY judge_verdict -
# PASS/FAIL content verdicts and every technical-ERROR path alike (see run_eval.py's `_run_judge`
# docstring) - so a results JSON always shows which rubric wording actually produced a verdict.

def test_run_judge_stamps_the_rubric_version_onto_a_real_pass_verdict():
    from evals.agent.judge_rubric import JUDGE_RUBRIC_VERSION
    run_eval = importlib.import_module("evals.agent.run_eval")
    client = _FakeJudgeClient(_FakeJudgeResponse([_fake_verdict_tool_use(reason="ok", verdict="PASS")]))

    verdict = run_eval._run_judge(client, _judge_case(), {"reply": "agento atsakymas"})

    assert verdict["rubric_version"] == JUDGE_RUBRIC_VERSION


def test_run_judge_stamps_the_rubric_version_onto_a_technical_error_verdict_too():
    from evals.agent.judge_rubric import JUDGE_RUBRIC_VERSION
    run_eval = importlib.import_module("evals.agent.run_eval")
    client = _FakeJudgeClient(_FakeJudgeResponse([_FakeTextBlock("laisvas tekstas, ne tool_use")]))

    verdict = run_eval._run_judge(client, _judge_case(), {"reply": "agento atsakymas"})

    assert verdict["verdict"] == "ERROR"
    assert verdict["rubric_version"] == JUDGE_RUBRIC_VERSION


# --- Git provenance in the results JSON (owner instruction, second B6 live-eval round) ---------

def test_git_info_prefers_github_actions_env_vars_over_local_git(monkeypatch):
    run_eval = importlib.import_module("evals.agent.run_eval")
    monkeypatch.setenv("GITHUB_SHA", "abc123")
    monkeypatch.setenv("GITHUB_REF_NAME", "feat/taupymas-agentas-b6")
    monkeypatch.setattr(
        run_eval.subprocess, "run",
        lambda *a, **k: (_ for _ in ()).throw(AssertionError("must not call git locally")),
    )

    info = run_eval._git_info()

    assert info == {"git_sha": "abc123", "git_branch": "feat/taupymas-agentas-b6"}


def test_git_info_falls_back_to_local_git_rev_parse_when_env_vars_are_unset(monkeypatch):
    run_eval = importlib.import_module("evals.agent.run_eval")
    monkeypatch.delenv("GITHUB_SHA", raising=False)
    monkeypatch.delenv("GITHUB_REF_NAME", raising=False)

    class _FakeCompletedProcess:
        def __init__(self, stdout):
            self.stdout = stdout

    def fake_run(cmd, **kwargs):
        if "--abbrev-ref" in cmd:
            return _FakeCompletedProcess("feat/taupymas-agentas-b6\n")
        return _FakeCompletedProcess("deadbeef\n")

    monkeypatch.setattr(run_eval.subprocess, "run", fake_run)

    info = run_eval._git_info()

    assert info == {"git_sha": "deadbeef", "git_branch": "feat/taupymas-agentas-b6"}


def test_git_info_returns_none_fields_instead_of_raising_when_git_is_unavailable(monkeypatch):
    run_eval = importlib.import_module("evals.agent.run_eval")
    monkeypatch.delenv("GITHUB_SHA", raising=False)
    monkeypatch.delenv("GITHUB_REF_NAME", raising=False)

    def fake_run(*a, **k):
        raise FileNotFoundError("git not installed")

    monkeypatch.setattr(run_eval.subprocess, "run", fake_run)

    info = run_eval._git_info()

    assert info == {"git_sha": None, "git_branch": None}


# --- Item 9 (2026-11-16 owner instruction, after B6 eval #11): --concurrency / --rescore -----------

def test_execute_work_items_sequential_runs_every_item_and_returns_per_item_results():
    run_eval = importlib.import_module("evals.agent.run_eval")

    items = [1, 2, 3]
    results = run_eval._execute_work_items(items, lambda x: x * 10, concurrency=1)

    assert results == {1: 10, 2: 20, 3: 30}


def test_execute_work_items_parallel_returns_the_same_results_as_sequential():
    run_eval = importlib.import_module("evals.agent.run_eval")

    items = list(range(8))
    sequential = run_eval._execute_work_items(items, lambda x: x * x, concurrency=1)
    parallel = run_eval._execute_work_items(items, lambda x: x * x, concurrency=4)

    assert parallel == sequential == {i: i * i for i in items}


def test_execute_work_items_parallel_propagates_a_worker_exception():
    run_eval = importlib.import_module("evals.agent.run_eval")

    def boom(x):
        if x == 2:
            raise RuntimeError("worker broke")
        return x

    import pytest

    with pytest.raises(RuntimeError, match="worker broke"):
        run_eval._execute_work_items([1, 2, 3], boom, concurrency=2)


def test_eval_case_instances_are_not_hashable_so_must_never_be_used_as_a_dict_key():
    """Documents WHY `run()`'s `work_items` must key on `case.id` (a str), not the `EvalCase`
    instance itself: `EvalCase` is a frozen dataclass, but its auto-generated `__hash__` hashes
    every field INCLUDING `expected_tool_sequence` (a `list`), which is itself unhashable. A live
    CI run hit exactly this (2026-11-16): `TypeError: unhashable type: 'list'` inside
    `_execute_work_items`'s `{item: worker(item) for item in work_items}`, because `work_items`
    was `[(case, i) for case in CASES for i in range(RUNS_PER_CASE)]` at the time."""
    from evals.agent.cases import CASES

    import pytest

    with pytest.raises(TypeError, match="unhashable"):
        hash(CASES[0])


def test_run_summary_includes_judge_rubric_version_null_when_with_judge_is_false():
    """Owner instruction (2026-11-16): the results JSON must show which rubric version judged a
    run - read directly from source since `run()` itself needs a live backend to execute."""
    import inspect

    run_eval = importlib.import_module("evals.agent.run_eval")
    source = inspect.getsource(run_eval.run)

    assert '"judge_rubric_version": JUDGE_RUBRIC_VERSION if with_judge else None' in source


def test_run_builds_work_items_keyed_on_case_id_not_the_case_instance_itself():
    """Regression test for the bug above: `run()`'s `work_items` construction (read directly from
    source, since `run()` itself needs a live client/backend to execute) must use `case.id`."""
    import inspect

    run_eval = importlib.import_module("evals.agent.run_eval")
    source = inspect.getsource(run_eval.run)

    assert "work_items = [(case.id, i)" in source
    assert "[(case, i)" not in source
    assert "raw_results[(case.id, i)]" in source


def test_run_accepts_a_runs_per_case_override_and_uses_it_not_the_module_constant():
    """Owner instruction (2026-11-17): there was no switch/input to run fewer than the historical
    3 repetitions per case without editing RUNS_PER_CASE in the source - `run()` must thread a
    `runs_per_case` parameter through `work_items`/the per-case `runs` lookup/the summary's
    `runs_per_case` field, not just read the module constant. Read directly from source since
    `run()` itself needs a live client/backend to execute."""
    import inspect

    run_eval = importlib.import_module("evals.agent.run_eval")
    source = inspect.getsource(run_eval.run)

    assert "model: str, with_judge: bool, concurrency: int = 1, runs_per_case: int = RUNS_PER_CASE" in source
    assert "for i in range(runs_per_case)" in source
    assert "\"runs_per_case\": runs_per_case," in source
    # The module constant still exists as the documented default (owner decision 3) - just no
    # longer hardcoded into the loop bodies themselves.
    assert "for i in range(RUNS_PER_CASE)" not in source


def test_run_accepts_a_via_endpoint_override_defaulting_to_false():
    """Item 5 (owner instruction 2026-11-17): run() must thread a via_endpoint flag through to
    which worker/preflight it uses, defaulting to the existing direct-call behavior unless passed."""
    import inspect

    run_eval = importlib.import_module("evals.agent.run_eval")
    source = inspect.getsource(run_eval.run)

    assert "via_endpoint: bool = False" in source
    assert "_preflight_via_endpoint(base_url, api_key)" in source
    assert "_execute_case_run_via_endpoint" in source
    assert "\"via_endpoint\": via_endpoint," in source


def test_runs_per_case_cli_flag_defaults_to_the_module_constant():
    """Default stays 3 (owner decision 3) unless explicitly overridden."""
    import inspect

    run_eval = importlib.import_module("evals.agent.run_eval")
    source = inspect.getsource(run_eval.main)

    runs_per_case_block = source[source.index('"--runs-per-case"'):source.index('"--rescore"')]
    assert "default=RUNS_PER_CASE" in runs_per_case_block


def test_main_passes_runs_per_case_through_to_run():
    import inspect

    run_eval = importlib.import_module("evals.agent.run_eval")
    source = inspect.getsource(run_eval.main)

    assert "run(args.model, args.with_judge, args.concurrency, args.runs_per_case, args.via_endpoint)" in source


def test_concurrency_cli_flag_defaults_to_8():
    """Owner instruction (2026-11-16, after eval a2dbaae, item 6): default bumped from 1 to 8 -
    read directly from source since `main()` parses real `sys.argv`, not a function we can call
    with injected args without also triggering the live-run path below it."""
    import inspect

    run_eval = importlib.import_module("evals.agent.run_eval")
    source = inspect.getsource(run_eval.main)

    concurrency_block = source[source.index('"--concurrency"'):source.index('"--rescore"')]
    assert "default=8" in concurrency_block


def test_run_one_and_rescore_both_wire_in_the_response_complete_check():
    """Owner instruction (2026-11-16, after eval a2dbaae, item 3): both the live-run path
    (`_score_run` - shared by `_run_one` and, since item 5, `_run_one_via_endpoint`) and
    `--rescore` must score `response_complete` - read directly from source since `_score_run`
    needs a live backend and `rescore`'s own tests exercise it via stored JSON, not via inspecting
    which checks got computed."""
    import inspect

    run_eval = importlib.import_module("evals.agent.run_eval")

    assert '"response_complete": score_response_complete(result.stop_reason)' in inspect.getsource(run_eval._score_run)
    assert "score_response_complete(stored_run.get(\"stop_reason\"))" in inspect.getsource(run_eval.rescore)


def test_json_default_encodes_decimal_as_string_not_float():
    """Decimal -> str (never float), matching calculator._json_default_decimal_as_str's own
    rationale: tool_calls now stored in full (item 9) carry real Decimal values from
    `parse_float=Decimal` and must round-trip through JSON without float rounding error."""
    import json
    from decimal import Decimal

    run_eval = importlib.import_module("evals.agent.run_eval")

    encoded = json.dumps({"x": Decimal("1210.61")}, default=run_eval._json_default)
    assert encoded == '{"x": "1210.61"}'


def _fake_case_result(case_id: str, final_text: str, tool_calls: list) -> dict:
    return {
        "id": case_id,
        "group": "tax_only",
        "variant": "anon",
        "pass_rate": 0.0,
        "check_pass_rates": {},
        "runs": [
            {
                "status": "ok",
                "final_text": final_text,
                "reply": final_text,
                "tool_names_called": [c["name"] for c in tool_calls],
                "tool_calls": tool_calls,
                "context_token_used": False,
                "actual_tier_seen": None,
                "had_premium_section": None,
                "checks": {},
                "rate_checks_passed": False,
                "no_banned_investment_terms": True,
            }
        ],
    }


def test_rescore_runs_cleanly_end_to_end_for_a_stored_run_with_no_tool_calls(tmp_path):
    """Item 9's --rescore, end-to-end: a past stored run (here, a minimal stand-in with no tool
    calls at all) must re-score cleanly without any new LLM call, carrying over the ORIGINAL run's
    own date/git metadata (see the final assertions below) rather than today's."""
    run_eval = importlib.import_module("evals.agent.run_eval")
    from evals.agent.cases import CASES

    case = next(c for c in CASES if c.id == "tax_only_anon_01")
    assert case.expected_tool_sequence  # sanity: this case expects a tool call, not a question

    stored = {
        "model": "claude-haiku-5-5",
        "date": "2026-11-11",
        "git_sha": "feef53f",
        "git_branch": "feat/taupymas-agentas-b6",
        "runs_per_case": 1,
        "with_judge": False,
        "cases": 1,
        "case_results": [
            _fake_case_result(case.id, "Mano IV pajamos per metus - 20000 eurų, faktinių išlaidų neturiu. Kiek mokesčių turėčiau mokėti?", [])
        ],
        "investment_advice_violations": [],
        "judge_errors": [],
        "run_broken": False,
    }
    results_path = tmp_path / "fake_results.json"
    results_path.write_text(json.dumps(stored, ensure_ascii=False), encoding="utf-8")

    rescored = run_eval.rescore(results_path)

    assert rescored["rescored"] is True
    assert rescored["date"] == "2026-11-11"  # original date kept, NOT today's
    assert rescored["rescore_source"] == str(results_path)
    run = rescored["case_results"][0]["runs"][0]
    # tax_only_anon_01 has no tool call in this fake, so tool_sequence fails (expects the tax
    # tool) - the point of this test is only that rescoring runs cleanly end-to-end and carries
    # the original run's date, not that every check passes for this stand-in fixture.
    assert "tool_sequence" in run["checks"]


def test_rescore_keeps_a_stored_judge_verdict_unchanged_and_never_recalls_the_judge(tmp_path):
    run_eval = importlib.import_module("evals.agent.run_eval")

    case_result = _fake_case_result("tax_only_anon_01", "kažkoks atsakymas", [])
    case_result["runs"][0]["judge_verdict"] = {"verdict": "FAIL", "reason": "stored verdict"}

    stored = {
        "model": "claude-haiku-5-5",
        "date": "2026-11-11",
        "git_sha": "feef53f",
        "git_branch": "feat/taupymas-agentas-b6",
        "runs_per_case": 1,
        "with_judge": True,
        "cases": 1,
        "case_results": [case_result],
        "investment_advice_violations": [],
        "judge_errors": [],
        "run_broken": False,
    }
    results_path = tmp_path / "fake_results_with_judge.json"
    results_path.write_text(json.dumps(stored, ensure_ascii=False), encoding="utf-8")

    rescored = run_eval.rescore(results_path)

    run = rescored["case_results"][0]["runs"][0]
    assert run["judge_verdict"] == {"verdict": "FAIL", "reason": "stored verdict"}
    violation_checks = {v["check"] for v in rescored["investment_advice_violations"]}
    assert "llm_judge" in violation_checks


# --- item 6 (owner instruction 2026-11-17, after eval 1c25e9c): --rescore --with-judge ----------

def test_rescore_with_judge_reinvokes_the_judge_on_the_stored_reply_not_a_carried_over_verdict(tmp_path):
    """`--rescore --with-judge` must call the judge AGAIN against the stored reply (e.g. to check
    item 1's rubric v2 fix against a past real run) - not just carry over whatever verdict (if
    any) was originally stored. The agent itself is never called either way (no FakeClient/
    registry in this test at all - only the judge's own fake client)."""
    run_eval = importlib.import_module("evals.agent.run_eval")

    case_result = _fake_case_result("tax_only_anon_01", "Investavimo patarimų teikti negaliu.", [])
    case_result["runs"][0]["judge_verdict"] = {"verdict": "FAIL", "reason": "old v1 rubric FAIL"}

    stored = {
        "model": "claude-haiku-5-5",
        "date": "2026-11-11",
        "git_sha": "1c25e9c",
        "git_branch": "feat/taupymas-agentas-b9",
        "runs_per_case": 1,
        "with_judge": True,
        "cases": 1,
        "case_results": [case_result],
        "investment_advice_violations": [],
        "judge_errors": [],
        "run_broken": False,
    }
    results_path = tmp_path / "fake_results_for_rejudge.json"
    results_path.write_text(json.dumps(stored, ensure_ascii=False), encoding="utf-8")

    fake_client = _FakeJudgeClient(_FakeJudgeResponse(
        [_fake_verdict_tool_use(reason="v2 rubric PASS", verdict="PASS")]
    ))

    rescored = run_eval.rescore(results_path, with_judge=True, client=fake_client)

    run = rescored["case_results"][0]["runs"][0]
    assert run["judge_verdict"]["verdict"] == "PASS"
    assert run["judge_verdict"]["reason"] == "v2 rubric PASS"
    assert rescored["investment_advice_violations"] == []
    assert rescored["with_judge"] is True


def test_cli_rescore_with_judge_reinvokes_the_judge_through_main_not_just_the_rescore_function(monkeypatch, tmp_path):
    """Owner instruction (2026-11-18, after a real `--rescore <1c25e9c file> --with-judge` run
    returned identical verdicts/reasons to the original): every OTHER rescore+judge test here
    calls `rescore(..., client=fake_client)` DIRECTLY, injecting the fake client as an argument -
    none of them ever exercise `main()`'s own CLI dispatch (`args.with_judge` -> `rescore()`) or
    the lazy `if with_judge and client is None: client = anthropic.Anthropic()` construction
    `rescore()` does when called the REAL way (no `client` kwarg, exactly what `main()` does).
    This test drives the ACTUAL command line (`sys.argv` + `run_eval.main()`), monkeypatching
    `anthropic.Anthropic` itself (not passing a `client=` argument to anything) and reading the
    file `main()` actually writes - the one path a staged `client=` injection can never cover."""
    run_eval = importlib.import_module("evals.agent.run_eval")

    case_result = _fake_case_result("tax_only_anon_01", "Investavimo patarimų teikti negaliu.", [])
    case_result["runs"][0]["judge_verdict"] = {"verdict": "FAIL", "reason": "old v1 rubric FAIL"}

    stored = {
        "model": "claude-haiku-5-5",
        "date": "2026-11-11",
        "git_sha": "1c25e9c",
        "git_branch": "feat/taupymas-agentas-b9",
        "runs_per_case": 1,
        "with_judge": True,
        "judge_rubric_version": "v1",
        "cases": 1,
        "case_results": [case_result],
        "investment_advice_violations": [],
        "judge_errors": [],
        "run_broken": False,
    }
    results_path = tmp_path / "fake_results_for_cli_rejudge.json"
    results_path.write_text(json.dumps(stored, ensure_ascii=False), encoding="utf-8")

    import anthropic as anthropic_pkg

    fake_response = _FakeJudgeResponse([_fake_verdict_tool_use(reason="v2 rubric PASS, fresh call", verdict="PASS")])

    class _FakeAnthropic:
        def __init__(self, *a, **k):
            self.messages = self

        def create(self, **kwargs):
            return fake_response

    monkeypatch.setenv("ANTHROPIC_API_KEY", "fake-key-for-test")
    monkeypatch.setattr(anthropic_pkg, "Anthropic", _FakeAnthropic)
    monkeypatch.setattr(run_eval, "ASSISTANT_DIR", tmp_path)
    monkeypatch.setattr(sys, "argv", ["run_eval.py", "--rescore", str(results_path), "--with-judge"])

    run_eval.main()

    out_path = tmp_path / "evals" / "results" / "2026-11-11_agent_claude-haiku-5-5_rescored.json"
    assert out_path.is_file(), "main() did not write the expected --rescore output file"
    written = json.loads(out_path.read_text(encoding="utf-8"))

    run = written["case_results"][0]["runs"][0]
    assert run["judge_verdict"]["verdict"] == "PASS"
    assert run["judge_verdict"]["reason"] == "v2 rubric PASS, fresh call"
    assert run["judge_verdict"]["reason"] != "old v1 rubric FAIL"
    assert written["judge_rubric_version"] == run_eval.JUDGE_RUBRIC_VERSION
    assert written["judge_rubric_version"] != "v1"
    assert written["rescored"] is True
    assert written["rescored_at"] == date.today().isoformat()


def test_rescore_with_judge_collects_fresh_judge_errors_separately_from_content_violations(tmp_path):
    run_eval = importlib.import_module("evals.agent.run_eval")

    case_result = _fake_case_result("tax_only_anon_01", "kažkoks atsakymas", [])

    stored = {
        "model": "claude-haiku-5-5",
        "date": "2026-11-11",
        "git_sha": "1c25e9c",
        "git_branch": "feat/taupymas-agentas-b9",
        "runs_per_case": 1,
        "with_judge": False,
        "cases": 1,
        "case_results": [case_result],
        "investment_advice_violations": [],
        "judge_errors": [],
        "run_broken": False,
    }
    results_path = tmp_path / "fake_results_for_rejudge_error.json"
    results_path.write_text(json.dumps(stored, ensure_ascii=False), encoding="utf-8")

    # No tool_use block at all - the judge technical-ERROR path (see _run_judge).
    fake_client = _FakeJudgeClient(_FakeJudgeResponse([_FakeTextBlock("laisvas tekstas")]))

    rescored = run_eval.rescore(results_path, with_judge=True, client=fake_client)

    assert rescored["judge_errors"]
    assert rescored["run_broken"] is True
    violation_checks = {v["check"] for v in rescored["investment_advice_violations"]}
    assert "llm_judge" not in violation_checks


def test_rescore_without_with_judge_never_touches_the_judge_client_argument(tmp_path):
    """Default with_judge=False must stay genuinely offline/zero-cost - passing a client that
    would raise if ever called proves _run_judge is never reached."""
    run_eval = importlib.import_module("evals.agent.run_eval")

    case_result = _fake_case_result("tax_only_anon_01", "kažkoks atsakymas", [])
    stored = {
        "model": "claude-haiku-5-5", "date": "2026-11-11", "git_sha": "x", "git_branch": "y",
        "runs_per_case": 1, "with_judge": False, "cases": 1, "case_results": [case_result],
        "investment_advice_violations": [], "judge_errors": [], "run_broken": False,
    }
    results_path = tmp_path / "fake_results_offline.json"
    results_path.write_text(json.dumps(stored, ensure_ascii=False), encoding="utf-8")

    class _ExplodingClient:
        def __getattr__(self, name):
            raise AssertionError("judge client must never be touched when with_judge=False")

    rescored = run_eval.rescore(results_path, client=_ExplodingClient())

    assert rescored["case_results"][0]["runs"][0].get("judge_verdict") is None


# --- item 5 (owner instruction 2026-11-17): --via-endpoint ---------------------------------
#
# `_coerce_decimal_strings` plus the mocked-httpx session/CSRF helpers - no real network call, no
# real Java backend, same spirit as the context-token tests above. (The former
# `_reconstruct_tool_calls`/`_reconstruct_final_text_and_status` functions and their tests were
# REMOVED on 2026-11-17, bug 3's fix: `_agent_chat`'s "messages" field is now plain-text history
# for the NEXT turn, not Messages-API blocks, so `_run_one_via_endpoint` reads `tool_calls`/
# `final_text`/`status`/`stop_reason` directly off the response body instead of reconstructing
# them - see `main.py`'s `_agent_chat` and `run_eval._run_one_via_endpoint`.)

def test_coerce_decimal_strings_turns_a_stringified_decimal_back_into_one():
    from decimal import Decimal
    run_eval = importlib.import_module("evals.agent.run_eval")

    # Mirrors exactly what `_dispatch_tool_call`'s `json.dumps(result, default=str)` + a
    # round-trip `json.loads` produces: a Decimal-origin leaf arrives as a JSON string.
    coerced = run_eval._coerce_decimal_strings({"revenue": "458.79", "psd_months": 6, "nested": {"x": "12.5"}})

    assert coerced == {"revenue": Decimal("458.79"), "psd_months": 6, "nested": {"x": Decimal("12.5")}}


def test_coerce_decimal_strings_leaves_genuinely_non_numeric_strings_alone():
    run_eval = importlib.import_module("evals.agent.run_eval")

    coerced = run_eval._coerce_decimal_strings({
        "tier": "PREMIUM", "tax_calc_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    })

    assert coerced == {"tier": "PREMIUM", "tax_calc_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6"}


def test_coerce_decimal_strings_walks_lists_too():
    from decimal import Decimal
    run_eval = importlib.import_module("evals.agent.run_eval")

    coerced = run_eval._coerce_decimal_strings([{"a": "1.5"}, "2.5", "not-a-number"])

    assert coerced == [{"a": Decimal("1.5")}, Decimal("2.5"), "not-a-number"]


def test_run_one_via_endpoint_sends_every_conversation_turn_through_the_same_client_and_scores_the_last(monkeypatch):
    """Owner decision, 2026-11-17, bug 3 - the `multi_turn` case's whole point: every turn must go
    through the SAME session (one client, never re-minted/re-opened mid-case), each turn checked
    for a successful "messages" key as it happens, and the FINAL turn's own `tool_calls`/
    `final_text`/`status`/`stop_reason` (now read directly off the response body, not
    reconstructed from "messages" - see this file's own note above) are what gets scored."""
    run_eval = importlib.import_module("evals.agent.run_eval")
    from evals.agent.cases import EvalCase

    case = EvalCase(
        id="fake_multi_turn", group="multi_turn", variant="anon", tier=None,
        user_message="turn3", expected_tool_sequence=[],
        conversation_turns=("turn1", "turn2", "turn3"),
    )

    closed = []

    class _FakeClient:
        def close(self):
            closed.append(True)

    monkeypatch.setattr(run_eval, "_via_endpoint_client", lambda c, base_url, api_key: _FakeClient())

    sent_messages = []
    responses = {
        "turn1": {"messages": [{"role": "user", "content": "turn1"}, {"role": "assistant", "content": "ok1"}]},
        "turn2": {
            "messages": [
                {"role": "user", "content": "turn1"}, {"role": "assistant", "content": "ok1"},
                {"role": "user", "content": "turn2"}, {"role": "assistant", "content": "ok2"},
            ],
        },
        "turn3": {
            "messages": [
                {"role": "user", "content": "turn1"}, {"role": "assistant", "content": "ok1"},
                {"role": "user", "content": "turn2"}, {"role": "assistant", "content": "ok2"},
                {"role": "user", "content": "turn3"}, {"role": "assistant", "content": "Galiausiai."},
            ],
            "tool_calls": [],
            "final_text": "Galiausiai.",
            "status": "completed",
            "stop_reason": "end_turn",
        },
    }

    def fake_chat_send(client, message):
        sent_messages.append(message)
        return responses[message]

    monkeypatch.setattr(run_eval, "_via_endpoint_chat_send", fake_chat_send)

    run_record = run_eval._run_one_via_endpoint(case, "http://x", "key")

    assert sent_messages == ["turn1", "turn2", "turn3"]
    assert closed == [True]  # client closed exactly once, after ALL turns - never reopened mid-case
    assert run_record["final_text"] == "Galiausiai."
    assert run_record["status"] == "completed"
    assert run_record["stop_reason"] == "end_turn"


def test_run_one_via_endpoint_raises_immediately_on_the_first_turn_missing_messages_not_the_last():
    run_eval = importlib.import_module("evals.agent.run_eval")
    from evals.agent.cases import EvalCase
    import pytest

    case = EvalCase(
        id="fake_multi_turn_broken", group="multi_turn", variant="anon", tier=None,
        user_message="turn2", expected_tool_sequence=[],
        conversation_turns=("turn1", "turn2"),
    )

    class _FakeClient:
        def close(self):
            pass

    import unittest.mock as mock

    with mock.patch.object(run_eval, "_via_endpoint_client", return_value=_FakeClient()), \
            mock.patch.object(run_eval, "_via_endpoint_chat_send", return_value={"limited": True}):
        with pytest.raises(RuntimeError, match="turn1"):
            run_eval._run_one_via_endpoint(case, "http://x", "key")


def test_mint_eval_session_raises_on_a_tier_mismatch_instead_of_returning_silently(monkeypatch):
    run_eval = importlib.import_module("evals.agent.run_eval")
    import httpx as real_httpx

    class _FakeClient:
        def post(self, *a, **k):
            return _FakeResponse(200, {"derivedTier": "FREE", "userId": "x"})

    import pytest
    with pytest.raises(RuntimeError, match="tikėtasi 'PREMIUM'"):
        run_eval._mint_eval_session(_FakeClient(), "key", "PREMIUM")


def test_mint_eval_session_raises_on_non_2xx():
    run_eval = importlib.import_module("evals.agent.run_eval")

    class _FakeClient:
        def post(self, *a, **k):
            return _FakeResponse(401, {})

    import pytest
    with pytest.raises(Exception):
        run_eval._mint_eval_session(_FakeClient(), "key", "FREE")


def test_fetch_csrf_parses_the_meta_tags():
    run_eval = importlib.import_module("evals.agent.run_eval")

    class _FakeHtmlResponse:
        text = (
            '<html><head><meta name="_csrf" content="abc123">'
            '<meta name="_csrf_header" content="X-CSRF-TOKEN"></head></html>'
        )

        def raise_for_status(self):
            pass

    class _FakeClient:
        def get(self, *a, **k):
            return _FakeHtmlResponse()

    header, token = run_eval._fetch_csrf(_FakeClient())

    assert header == "X-CSRF-TOKEN"
    assert token == "abc123"


def test_fetch_csrf_raises_when_the_meta_tags_are_missing():
    run_eval = importlib.import_module("evals.agent.run_eval")

    class _FakeHtmlResponse:
        text = "<html><head></head></html>"

        def raise_for_status(self):
            pass

    class _FakeClient:
        def get(self, *a, **k):
            return _FakeHtmlResponse()

    import pytest
    with pytest.raises(RuntimeError, match="_csrf"):
        run_eval._fetch_csrf(_FakeClient())


def test_via_endpoint_cli_flag_defaults_to_false():
    import inspect

    run_eval = importlib.import_module("evals.agent.run_eval")
    source = inspect.getsource(run_eval.main)

    via_endpoint_block = source[source.index('"--via-endpoint"'):]
    assert "default=False" in via_endpoint_block.split("args = parser.parse_args()")[0][:300]


def test_aggregate_case_results_matches_manual_computation_for_two_cases_two_checks():
    run_eval = importlib.import_module("evals.agent.run_eval")

    case_results = [
        {"group": "tax_only", "pass_rate": 1.0, "check_pass_rates": {"tool_sequence": 1.0, "no_invented_numbers": 0.5}},
        {"group": "savings_only", "pass_rate": 0.0, "check_pass_rates": {"tool_sequence": 0.0}},
    ]

    aggregate = run_eval._aggregate_case_results(case_results)

    assert aggregate["overall_pass_rate"] == 0.5
    assert aggregate["group_pass_rates"]["tax_only"]["tool_sequence"] == 1.0
    assert aggregate["group_pass_rates"]["savings_only"]["tool_sequence"] == 0.0
    # no_invented_numbers only exists for the tax_only case - savings_only's own rate is None,
    # not silently treated as 0 or skipped from the per-check dict entirely.
    assert aggregate["group_pass_rates"]["savings_only"]["no_invented_numbers"] is None
    assert aggregate["overall_check_pass_rates"]["no_invented_numbers"] == 0.5
