"""Per-incoming-message context (B0, AC-B0-3; design decision 2 in the B plan).

One `RequestContext` is constructed per incoming request (today: `/asistentas`; later: the chat
endpoint), holds that message's identity and any intermediate tool results, and is discarded once
the response is sent. It is never a module-level/shared singleton - two requests must never see
each other's results.
"""
import uuid
from dataclasses import dataclass, field
from typing import Any, Optional


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

    # TODO(B2, AC-B2-2): an unknown id must become a defined tool error (not an exception that
    # kills the turn), mirroring ToolRegistry.dispatch's UnknownToolError. Bare KeyError is fine
    # for B0 (no consumer needs a defined error type yet) but must be replaced before B2 relies
    # on this.
    def get_result(self, result_id: str) -> Any:
        return self.results[result_id]
