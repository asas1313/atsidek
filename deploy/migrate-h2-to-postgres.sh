#!/usr/bin/env bash
# Perkelia duomenis iš seno H2 failo (prieš F0 fazės PostgreSQL migraciją) į naują PostgreSQL DB.
#
# SVARBU – PERSKAITYK PRIEŠ VYKDYMĄ:
#   - Šis skriptas NIEKADA nevykdomas automatiškai (CI, deploy.sh jo nekviečia). Vykdo ŽMOGUS,
#     rankomis, su tiesiogine prieiga prie serverio ir tinklo (192.168.68.106).
#   - Paskirties PostgreSQL duomenų bazėje PRIEŠ ŠĮ SKRIPTĄ turi būti PALEISTA aplikacija bent
#     vieną kartą (arba `mvn flyway:migrate`), kad Flyway sukurtų tuščias lenteles (V1, V2).
#     Skriptas atsisako dirbti, jei paskirties lentelės NĖRA tuščios (žr. Migrate.java).
#   - PADARYK Postgres BACKUP (pg_dump) prieš vykdymą – tai negrįžtama operacija be papildomo
#     saugiklio, jei kažkas nepavyks pusiaukelėje.
#   - Rekomenduojama: pirma išbandyti su H2 failo KOPIJA ir staging Postgres DB, tik tada production.
#
# Naudojimas:
#   deploy/migrate-h2-to-postgres.sh <h2-data-failas-be-.mv.db> <pg-jdbc-url> <pg-user> <pg-password>
#
# Pavyzdys (staging):
#   deploy/migrate-h2-to-postgres.sh /opt/atsidek/staging-data/atsidek \
#     "jdbc:postgresql://192.168.68.106:5432/atsidek_staging" atsidek_staging '<slaptazodis>'
#
# H2 failas turi būti UŽDARYTAS (aplikacija sustabdyta), kitaip H2 gali atsisakyti prisijungti
# (DB_CLOSE_ON_EXIT=FALSE reiškia, kad failas liko atviras, kol JVM dirbo – sustabdžius app jis laisvas).

set -euo pipefail

H2_DATA_FILE="${1:?nurodyk H2 failo kelią BE .mv.db galūnės, pvz. /opt/atsidek/staging-data/atsidek}"
PG_URL="${2:?nurodyk Postgres JDBC URL, pvz. jdbc:postgresql://192.168.68.106:5432/atsidek_staging}"
PG_USER="${3:?nurodyk Postgres vartotoją}"
PG_PASSWORD="${4:?nurodyk Postgres slaptažodį}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "== 1/4: Tikrinu, ar H2 failas egzistuoja =="
if [ ! -f "${H2_DATA_FILE}.mv.db" ]; then
  echo "KLAIDA: nerasta ${H2_DATA_FILE}.mv.db" >&2
  exit 1
fi

echo "== 2/4: Parsisiunčiu H2 ir PostgreSQL JDBC tvarkykles (naudoja Maven lokalų cache, jei yra) =="
command -v mvn >/dev/null 2>&1 || { echo "KLAIDA: reikia Maven (mvn) tvarkyklių parsisiuntimui." >&2; exit 1; }
mvn -q -ntp dependency:copy \
  -Dartifact=com.h2database:h2:2.3.232 \
  -DoutputDirectory="$WORK_DIR" >/dev/null
mvn -q -ntp dependency:copy \
  -Dartifact=org.postgresql:postgresql:42.7.4 \
  -DoutputDirectory="$WORK_DIR" >/dev/null
H2_JAR="$WORK_DIR/h2-2.3.232.jar"
PG_JAR="$WORK_DIR/postgresql-42.7.4.jar"

echo "== 3/4: Kompiliuoju Migrate.java =="
command -v javac >/dev/null 2>&1 || { echo "KLAIDA: reikia JDK (javac)." >&2; exit 1; }
javac -cp "$H2_JAR:$PG_JAR" -d "$WORK_DIR" "$SCRIPT_DIR/migrate-h2-to-postgres/Migrate.java"

echo "== 4/4: Vykdau migraciją =="
echo "H2 (read-only, MODE=PostgreSQL;ACCESS_MODE_DATA=r): ${H2_DATA_FILE}"
echo "PG paskirtis: ${PG_URL}"
read -r -p "Tęsti? Įvesk 'taip': " CONFIRM
if [ "$CONFIRM" != "taip" ]; then
  echo "Atšaukta."
  exit 1
fi

java -cp "$H2_JAR:$PG_JAR:$WORK_DIR" Migrate \
  "jdbc:h2:file:${H2_DATA_FILE};MODE=PostgreSQL;ACCESS_MODE_DATA=r" "sa" "" \
  "$PG_URL" "$PG_USER" "$PG_PASSWORD"

echo "Baigta. Patikrink eilučių skaičius abiejose DB (pvz. SELECT count(*) FROM bank_transaction;)."
