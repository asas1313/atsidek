# Uniform interface used by agents, hooks and CI.
# Replace the bodies for your stack; keep the target names.
.PHONY: setup test e2e lint lint-changed format format-file security docs build deploy

setup:
	mvn -q -ntp -DskipTests dependency:go-offline
	cd assistant && ../.venv/bin/pip install -q -r requirements-dev.txt

# Mirrors .github/workflows/ci.yml: mvn test (includes Testcontainers-backed
# integration tests, no separate IT phase) + assistant pytest.
test:
	mvn -B -ntp -q test
	cd assistant && ../.venv/bin/python -m pytest -q

e2e:
	@echo "TODO: run Playwright e2e tests (not set up yet)"

# No checkstyle/spotbugs/ruff configured in this repo yet; nothing to run.
lint:
	@echo "lint: no linter configured yet for Java (checkstyle/spotbugs) or Python (ruff) in this repo"

lint-changed:
	@$(MAKE) -s lint

format:
	@echo "TODO: formatter (spotless:apply / ruff format) - not set up yet"

# Called by the post-edit hook with FILE=<path>; must be fast and must not fail the edit.
format-file:
	@true

# No dependency/SAST scanner configured in this repo yet.
security:
	@echo "security: no scanner configured yet (owasp dependency-check / semgrep) in this repo"

# No mkdocs site configured in this repo; specs live as plain Markdown under docs/.
docs:
	@echo "docs: no docs build configured in this repo (plain Markdown under docs/)"

build:
	mvn -q -ntp -DskipTests package

# Used only by CI. ENV=staging|production. Reads DEPLOY_* env vars.
deploy:
	@test -n "$(ENV)" || (echo "ENV is required" && exit 1)
	@echo "TODO: deploy build output to $$DEPLOY_HOST:$$DEPLOY_PATH (ENV=$(ENV))"
