from fastapi.testclient import TestClient

from app.main import create_app


def test_staging_is_noindex(monkeypatch):
    monkeypatch.setenv("ATSIDEK_NOINDEX", "true")
    r = TestClient(create_app()).get("/")
    assert r.headers["X-Robots-Tag"] == "noindex, nofollow"
    assert 'content="noindex,nofollow"' in r.text


def test_production_is_indexed(monkeypatch):
    monkeypatch.setenv("ATSIDEK_NOINDEX", "false")
    r = TestClient(create_app()).get("/")
    assert "X-Robots-Tag" not in r.headers
    assert 'content="index,follow"' in r.text
