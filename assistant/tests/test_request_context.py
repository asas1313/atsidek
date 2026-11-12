import pytest

from app.request_context import RequestContext, UnknownResultError


def test_unknown_result_id_raises_defined_error_not_bare_keyerror():
    """Prep for B2 (AC-B2-2): calculate_savings_plan looks up a tax_calc_id that may not exist
    (typo'd by the model, or from a different turn) and must get a defined, catchable error."""
    ctx = RequestContext()
    with pytest.raises(UnknownResultError):
        ctx.get_result("does-not-exist")


def test_context_starts_empty():
    ctx = RequestContext()
    assert ctx.results == {} and ctx.user_id is None


def test_store_and_retrieve_result_round_trips():
    ctx = RequestContext()
    result_id = ctx.store_result({"total": "500.50"})
    assert ctx.get_result(result_id) == {"total": "500.50"}


def test_two_instances_do_not_share_state():
    a = RequestContext(user_id="u1", tier="premium")
    b = RequestContext()
    a.store_result("only-in-a")
    assert b.results == {}
    assert a.user_id == "u1" and b.user_id is None
    assert a.tier == "premium" and b.tier == "anonymous"


def test_store_result_generates_distinct_ids():
    ctx = RequestContext()
    id1 = ctx.store_result("first")
    id2 = ctx.store_result("second")
    assert id1 != id2
    assert ctx.get_result(id1) == "first" and ctx.get_result(id2) == "second"
