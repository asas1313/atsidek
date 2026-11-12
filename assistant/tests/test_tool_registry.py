import pytest

from app.tools import ToolRegistry, UnknownToolError


def test_register_and_dispatch_by_name():
    registry = ToolRegistry()
    registry.register("echo", lambda text: f"echo:{text}")
    assert registry.dispatch("echo", "hi") == "echo:hi"


def test_register_as_decorator():
    registry = ToolRegistry()

    @registry.register("shout")
    def shout(text):
        return text.upper()

    assert registry.dispatch("shout", "hi") == "HI"
    assert shout("x") == "X"  # decorator returns the original callable unchanged


def test_registry_contains_expected_tool_names():
    registry = ToolRegistry()
    registry.register("a", lambda: None)
    registry.register("b", lambda: None)
    assert "a" in registry and "b" in registry and "c" not in registry
    assert set(registry.names) == {"a", "b"}


def test_dispatch_unknown_name_raises_defined_error_not_bare_keyerror():
    registry = ToolRegistry()
    with pytest.raises(UnknownToolError):
        registry.dispatch("does_not_exist")
    # Must not be a bare KeyError - UnknownToolError is its own type.
    assert not issubclass(UnknownToolError, KeyError)


def test_dispatch_passes_through_args_and_kwargs():
    registry = ToolRegistry()
    registry.register("add", lambda a, b=0: a + b)
    assert registry.dispatch("add", 2, b=3) == 5
