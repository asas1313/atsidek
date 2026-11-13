"""Smoke tests (B6): `evals/agent/run_eval.py` and `evals/agent/judge_rubric.py` must import
cleanly WITHOUT a live Anthropic client/API key or a live Java backend - this is a syntax/wiring
check only, never an execution of the costed live runner itself (that stays manual/
`workflow_dispatch`-only, per owner decision 3).
"""
import importlib
import subprocess
import sys
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


def test_judge_rubric_module_imports_and_exposes_draft_contract():
    judge_rubric = importlib.import_module("evals.agent.judge_rubric")

    assert judge_rubric.JUDGE_MODEL == "claude-sonnet-5-5"
    assert isinstance(judge_rubric.DRAFT_JUDGE_RUBRIC_PROMPT, str)
    assert judge_rubric.DRAFT_JUDGE_RUBRIC_PROMPT.strip()
    assert isinstance(judge_rubric.DRAFT_PASS_RATE_THRESHOLD, float)
    assert "DRAFT" in judge_rubric.__doc__
    assert "owner" in judge_rubric.__doc__.lower()


def test_build_judge_request_returns_a_messages_create_shaped_dict_without_calling_anthropic():
    judge_rubric = importlib.import_module("evals.agent.judge_rubric")

    request = judge_rubric.build_judge_request("vartotojo klausimas", "agento atsakymas")

    assert request["model"] == judge_rubric.JUDGE_MODEL
    assert request["system"] == judge_rubric.DRAFT_JUDGE_RUBRIC_PROMPT
    assert isinstance(request["messages"], list) and len(request["messages"]) == 1
    assert "vartotojo klausimas" in request["messages"][0]["content"]
    assert "agento atsakymas" in request["messages"][0]["content"]


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

class _FakeTextBlock:
    def __init__(self, text):
        self.type = "text"
        self.text = text


class _FakeThinkingBlock:
    """Adaptive thinking (owner instruction note): a thinking block must never be read as the
    judge's answer - only `type == "text"` blocks are joined."""
    def __init__(self):
        self.type = "thinking"
        self.text = "should not be read"


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


def test_run_judge_returns_error_not_fail_on_empty_text_block():
    run_eval = importlib.import_module("evals.agent.run_eval")
    client = _FakeJudgeClient(_FakeJudgeResponse([_FakeTextBlock("   ")]))

    verdict = run_eval._run_judge(client, _judge_case(), {"reply": "agento atsakymas"})

    assert verdict["verdict"] == "ERROR"
    assert "reason" in verdict


def test_run_judge_returns_error_not_fail_on_invalid_json():
    run_eval = importlib.import_module("evals.agent.run_eval")
    client = _FakeJudgeClient(_FakeJudgeResponse([_FakeTextBlock("not valid json{")]))

    verdict = run_eval._run_judge(client, _judge_case(), {"reply": "agento atsakymas"})

    assert verdict["verdict"] == "ERROR"


def test_run_judge_returns_error_not_fail_on_json_without_a_recognized_verdict_field():
    run_eval = importlib.import_module("evals.agent.run_eval")
    client = _FakeJudgeClient(_FakeJudgeResponse([_FakeTextBlock('{"verdict": "MAYBE"}')]))

    verdict = run_eval._run_judge(client, _judge_case(), {"reply": "agento atsakymas"})

    assert verdict["verdict"] == "ERROR"


def test_run_judge_ignores_thinking_blocks_and_reads_only_text_blocks():
    run_eval = importlib.import_module("evals.agent.run_eval")
    client = _FakeJudgeClient(_FakeJudgeResponse(
        [_FakeThinkingBlock(), _FakeTextBlock('{"verdict": "PASS"}')]
    ))

    verdict = run_eval._run_judge(client, _judge_case(), {"reply": "agento atsakymas"})

    assert verdict == {"verdict": "PASS"}


def test_run_judge_passes_through_a_real_fail_verdict_unchanged():
    run_eval = importlib.import_module("evals.agent.run_eval")
    client = _FakeJudgeClient(_FakeJudgeResponse(
        [_FakeTextBlock('{"verdict": "FAIL", "reason": "paminėjo konkretų fondą"}')]
    ))

    verdict = run_eval._run_judge(client, _judge_case(), {"reply": "agento atsakymas"})

    assert verdict == {"verdict": "FAIL", "reason": "paminėjo konkretų fondą"}
