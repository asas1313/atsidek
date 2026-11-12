"""Error-hierarchy tests (B2 review round 1, SHOULD #1).

`ToolError` is the base B3's agent loop is meant to catch to produce `is_error` tool_result
blocks. `CalculatorError` (Java 400/5xx/misconfig) and `UnknownToolError` (unregistered tool
name, B0) are both genuinely model-recoverable and must be part of that hierarchy - otherwise a
literal reading of errors.py's own rule ("anything NOT a ToolError must propagate uncaught")
would make B3 crash the turn instead of producing an is_error block.
"""
from app.calculator import CalculatorError
from app.errors import MissingIncomeError, ToolError
from app.tools import UnknownToolError


def test_calculator_error_is_a_tool_error():
    assert issubclass(CalculatorError, ToolError)


def test_unknown_tool_error_is_a_tool_error():
    assert issubclass(UnknownToolError, ToolError)


# --- tool_message (B3 review round 1, SHOULD #2) ------------------------------------------------
# `CalculatorError`'s constructor message was written B1-era for a human reading `/asistentas`'s
# result after typing bad free text - B3's agent loop now also relays SOME text from this same
# exception to the model as an `is_error` tool_result. `tool_message` lets the two readers see
# different wording from the same exception instance, without one call site having to guess.

def test_tool_error_base_tool_message_defaults_to_str():
    """A ToolError subclass that writes its message AS a model instruction already (e.g.
    MissingIncomeError, B2) needs no override - the default is exactly str(self)."""
    err = MissingIncomeError("Nėra pajamų - paklausk vartotojo.")
    assert err.tool_message == str(err) == "Nėra pajamų - paklausk vartotojo."


def test_calculator_error_tool_message_differs_from_the_human_facing_str():
    """The human-facing `message` (str(e), still what main.py's direct /asistentas path shows)
    must stay exactly as B1 wrote it; tool_message is a SEPARATE, model-actionable text."""
    err = CalculatorError("Skaičiuoklė atmetė skaičius. Patikslink tekstą.", status_code=400)
    assert str(err) == "Skaičiuoklė atmetė skaičius. Patikslink tekstą."
    assert err.tool_message != str(err)
    assert "nebandyk" in err.tool_message.lower() or "nebandyk" in err.tool_message


def test_calculator_error_tool_message_distinguishes_rejected_input_from_unreachable_service():
    rejected = CalculatorError("bad input", status_code=400)
    unreachable = CalculatorError("network error")  # no status_code - misconfig/unreachable
    assert rejected.tool_message != unreachable.tool_message
