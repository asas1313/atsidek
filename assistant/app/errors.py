"""Tool-handler error hierarchy (B2).

`ToolError` is the base for every error a tool handler can raise that B3's agent loop is meant to
catch and turn into an `is_error` tool_result block (the model sees the message and can recover
or ask the user - the turn does not crash). Anything that is NOT a `ToolError` (a bare KeyError,
an assertion, ...) is a real bug/contract violation and must propagate uncaught - B3 must not
swallow those either.

Review round 1 (SHOULD #1): `CalculatorError` (calculator.py - raised on a Java 400/5xx response
or a misconfigured client, e.g. AC-B2-3's "savings tool called with bad data, Java rejects it")
and `UnknownToolError` (tools.py, B0 - dispatching an unregistered tool name) are both genuinely
model-recoverable in exactly this sense, so both subclass `ToolError` too (declared in their own
modules, not here, to avoid errors.py importing FROM the modules that import errors.py).
"""


class ToolError(Exception):
    """Base class for all defined, model-recoverable tool-handler errors (B2/B3)."""


class UnknownResultError(ToolError):
    """Raised when looking up a result id that was never stored in this turn's RequestContext.

    Mirrors ToolRegistry.dispatch's UnknownToolError (B0): a defined, catchable error, not a bare
    KeyError that would kill the turn. First real consumer: B2's calculate_savings_plan tool,
    resolving a tax_calc_id produced by B1's calculate_iv_taxes.
    """


class MissingIncomeError(ToolError):
    """Raised when a savings-plan request has neither `tax_calc_id` nor `net_monthly_income` -
    PLAN.md principle: income is never guessed from statistics. The message is written AS AN
    INSTRUCTION TO THE MODEL (B2 owner decision), not just a human-readable description, since
    this is exactly the text the model will see inside the `is_error` tool_result block.
    """
