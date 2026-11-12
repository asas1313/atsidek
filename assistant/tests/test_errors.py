"""Error-hierarchy tests (B2 review round 1, SHOULD #1).

`ToolError` is the base B3's agent loop is meant to catch to produce `is_error` tool_result
blocks. `CalculatorError` (Java 400/5xx/misconfig) and `UnknownToolError` (unregistered tool
name, B0) are both genuinely model-recoverable and must be part of that hierarchy - otherwise a
literal reading of errors.py's own rule ("anything NOT a ToolError must propagate uncaught")
would make B3 crash the turn instead of producing an is_error block.
"""
from app.calculator import CalculatorError
from app.errors import ToolError
from app.tools import UnknownToolError


def test_calculator_error_is_a_tool_error():
    assert issubclass(CalculatorError, ToolError)


def test_unknown_tool_error_is_a_tool_error():
    assert issubclass(UnknownToolError, ToolError)
