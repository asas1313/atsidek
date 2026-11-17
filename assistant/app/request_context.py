"""Per-incoming-message context (B0, AC-B0-3; design decision 2 in the B plan).

One `RequestContext` is constructed per incoming request (today: `/asistentas`; later: the chat
endpoint), holds that message's identity and any intermediate tool results, and is discarded once
the response is sent. It is never a module-level/shared singleton - two requests must never see
each other's results.
"""
import uuid
from dataclasses import dataclass, field
from typing import Any, Optional

from .errors import UnknownResultError

# UnknownResultError is imported here purely to re-export it (review round 1 NIT #9) so existing
# test call sites (`from app.request_context import RequestContext, UnknownResultError`) keep
# working unchanged. `errors.py` is the canonical home for the class itself.
__all__ = ["RequestContext", "UnknownResultError"]


@dataclass
class RequestContext:
    user_id: Optional[str] = None
    tier: str = "anonymous"
    results: dict[str, Any] = field(default_factory=dict)
    # Owner decision, 2026-11-17 (hallucinated-income bug): the raw text of the CURRENT incoming
    # user message, so a tool handler (`savings_tool.py`'s heuristic guard) can check whether a
    # model-supplied figure actually appears in what the user said, instead of trusting it blindly.
    # Default "" (not every caller - e.g. /asistentas's own RequestContext - needs this at all).
    message_text: str = ""

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
