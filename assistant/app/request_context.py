"""Per-incoming-message context (B0, AC-B0-3; design decision 2 in the B plan).

One `RequestContext` is constructed per incoming request (today: `/asistentas`; later: the chat
endpoint), holds that message's identity and any intermediate tool results, and is discarded once
the response is sent. It is never a module-level/shared singleton - two requests must never see
each other's results.
"""
import uuid
from dataclasses import dataclass, field
from typing import Any, Optional


class UnknownResultError(Exception):
    """Raised when looking up a result id that was never stored in this turn's RequestContext.

    Mirrors ToolRegistry.dispatch's UnknownToolError (B0): a defined, catchable error a tool
    handler can turn into an `is_error` tool_result (B3), not a bare KeyError that would kill the
    turn. Added ahead of B2, whose calculate_savings_plan tool is the first real consumer of
    get_result() (resolving a tax_calc_id produced by B1's calculate_iv_taxes).
    """


@dataclass
class RequestContext:
    user_id: Optional[str] = None
    tier: str = "anonymous"
    results: dict[str, Any] = field(default_factory=dict)

    def store_result(self, value: Any) -> str:
        """Stores `value` under a freshly generated id and returns that id."""
        result_id = str(uuid.uuid4())
        self.results[result_id] = value
        return result_id

    def get_result(self, result_id: str) -> Any:
        try:
            return self.results[result_id]
        except KeyError:
            raise UnknownResultError(f"Nežinomas rezultato id: {result_id}") from None
