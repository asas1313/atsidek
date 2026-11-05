#!/usr/bin/env bash
# Diegia pasirinktą aplinką tame pačiame serveryje, kur veikia GitHub runner.
# Naudojimas: deploy/deploy.sh <staging|production> <git-sha>
#
# Kiekviena aplinka turi savo failą /opt/atsidek/<aplinka>.env (CONTROLLER_NAME,
# CONTACT_EMAIL, TUNNEL_TOKEN, APP_PORT, CANONICAL_HOST, NOINDEX, DATA_DIR,
# BANK_ENABLED, BANK_PASSWORD, BANK_IBAN_SALT).
set -euo pipefail

ENV_NAME="${1:?aplinka: staging arba production}"
SHA="${2:?git sha}"
ENV_FILE="/opt/atsidek/${ENV_NAME}.env"
[ -f "$ENV_FILE" ] || { echo "Nėra $ENV_FILE" >&2; exit 1; }

TAG="${SHA:0:12}"
PORT="$(grep -E '^APP_PORT=' "$ENV_FILE" | cut -d= -f2)"
PORT="${PORT:-8080}"
export IMAGE_TAG="$TAG"

# Duomenų katalogas (H2 failas) turi būti įrašomas konteinerio vartotojui (uid 10001)
DATA_DIR="$(grep -E '^DATA_DIR=' "$ENV_FILE" | cut -d= -f2)"
if [ -n "$DATA_DIR" ]; then mkdir -p "$DATA_DIR" && chmod 0777 "$DATA_DIR"; fi

COMPOSE=(docker compose -p "atsidek-${ENV_NAME}" --env-file "$ENV_FILE" --profile tunnel)

# Tas pats image tag'as: jei staging jau jį sukūrė, production naudoja būtent jį.
if ! docker image inspect "atsidek:${TAG}" >/dev/null 2>&1; then
  "${COMPOSE[@]}" build app
fi

"${COMPOSE[@]}" up -d

echo "Laukiama, kol ${ENV_NAME} atsakys (127.0.0.1:${PORT})..."
for _ in $(seq 1 30); do
  if curl -fsS -o /dev/null "http://127.0.0.1:${PORT}/"; then
    echo "OK: ${ENV_NAME} veikia (atsidek:${TAG})"
    docker image prune -f >/dev/null
    exit 0
  fi
  sleep 2
done

echo "KLAIDA: ${ENV_NAME} neatsako" >&2
"${COMPOSE[@]}" logs --tail 50 app >&2
exit 1
