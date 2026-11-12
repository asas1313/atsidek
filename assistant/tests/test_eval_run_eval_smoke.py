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


def test_cases_module_declares_exactly_the_expected_tool_name_strings():
    """Not tautological: hardcodes the real tool names independently of any implementation
    import, then checks cases.py's CASES only ever reference those two."""
    from evals.agent.cases import CASES

    known_tool_names = {"calculate_iv_taxes", "calculate_savings_plan"}
    for case in CASES:
        assert set(case.expected_tool_sequence) <= known_tool_names, case.id
