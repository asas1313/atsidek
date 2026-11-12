"""Tool registry: dict[str, Callable] from tool name to handler (B0, AC-B0-1).

Scaffolding for the agentic tool-calling loop that lands in B1/B3. For now it has one real
consumer: `/asistentas`'s existing extraction step, wired through unchanged (see main.py).
"""
from typing import Any, Callable, Optional

from .errors import ToolError


class UnknownToolError(ToolError):
    """Raised when dispatching a tool name that was never registered.

    Subclasses `ToolError` (review round 1 SHOULD #1): dispatching an unknown tool name is
    model-recoverable (B3's agent loop is meant to catch it and produce an `is_error`
    tool_result block), not a bug that should crash the turn.
    """


class ToolRegistry:
    """Name -> handler dispatch, never a dynamic/unregistered call (see B dalis plan risks)."""

    def __init__(self) -> None:
        self._tools: dict[str, Callable[..., Any]] = {}

    def register(self, name: str, handler: Optional[Callable[..., Any]] = None):
        """Register `handler` under `name`. Usable either directly or as a decorator:

        registry.register("foo", foo_fn)
        @registry.register("foo")
        def foo_fn(...): ...
        """
        if handler is not None:
            self._tools[name] = handler
            return handler

        def decorator(fn: Callable[..., Any]) -> Callable[..., Any]:
            self._tools[name] = fn
            return fn

        return decorator

    def dispatch(self, name: str, *args: Any, **kwargs: Any) -> Any:
        try:
            handler = self._tools[name]
        except KeyError:
            raise UnknownToolError(f"Nežinomas įrankis: {name}") from None
        return handler(*args, **kwargs)

    def __contains__(self, name: str) -> bool:
        return name in self._tools

    @property
    def names(self) -> list[str]:
        return list(self._tools)
