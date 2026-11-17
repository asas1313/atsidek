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

    @property
    def tool_message(self) -> str:
        """Text relayed to the model inside B3's `is_error` tool_result block (agent_loop.py).

        Defaults to `str(self)` - correct for every subclass whose constructor message is
        ALREADY written as an instruction to the model (e.g. `MissingIncomeError`). A subclass
        whose message was written for a different reader (e.g. `CalculatorError`, B1-era
        human-facing UI text) overrides this property instead of its `__str__`, so that other,
        non-agent callers of the same exception (`main.py`'s direct `/asistentas` endpoint)
        keep seeing the original human-facing wording unchanged.
        """
        return str(self)


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

    No longer raised by `savings_tool.resolve_income` itself (owner decision, 2026-11-17: income
    genuinely absent is handled by calling Java WITHOUT it, not by erroring - see that module's
    own docstring) - kept as a public, documented error class since nothing else depended on its
    removal, and `test_agent_loop.py`/`test_errors.py` use it as a generic `ToolError` example.
    """


class HallucinatedIncomeError(ToolError):
    """Owner decision, 2026-11-17 (real staging bug: for "Noriu sutaupyti 8000 eurų per 4 metus
    kelionei." the model computed 8000/4=2000 and passed it off as `net_monthly_income`, a
    number it invented from the GOAL itself, not from anything the user actually said about their
    income). Raised by `savings_tool.py`'s heuristic guard when a model-supplied income figure
    suspiciously equals the goal amount divided by its term (or that quotient times 12) AND that
    literal figure does not appear anywhere in the user's own message - PLAN.md's "income is
    never guessed" principle applies just as much to numbers derived from OTHER numbers in the
    request as it does to statistics. The message is written AS AN INSTRUCTION TO THE MODEL, same
    convention as `MissingIncomeError`.
    """
