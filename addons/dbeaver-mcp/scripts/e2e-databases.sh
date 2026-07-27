#!/usr/bin/env bash
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/paths.sh"
upstream=${DBEAVER_UPSTREAM:-"$default_upstream"}
common=${DBEAVER_COMMON:-"$default_common"}
compose=(docker compose -f "$repo_root/integration-tests/docker-compose.yml")
cleanup() { "${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT
"${compose[@]}" up -d --wait postgres mysql

"$common/mvnw" -q dependency:get -Dartifact=com.mysql:mysql-connector-j:9.3.0
pg_driver=$(find "$HOME/.m2/repository/org/postgresql/postgresql" -name 'postgresql-*.jar' | sort -V | tail -1)
mysql_driver=$(find "$HOME/.m2/repository/com/mysql/mysql-connector-j" -name 'mysql-connector-j-*.jar' | sort -V | tail -1)
gson=$(find "$HOME/.m2/repository" -type f -path '*/com/google/code/gson/gson/*/gson-*.jar' | sort -V | tail -1)
studio="$upstream/plugins/org.jkiss.dbeaver.teststudio.core/target/classes"
pg_classes="$upstream/plugins/org.jkiss.dbeaver.teststudio.db.postgresql/target/classes"
mysql_classes="$upstream/plugins/org.jkiss.dbeaver.teststudio.db.mysql/target/classes"
work="$repo_root/.work/database-e2e"
rm -rf "$work" && mkdir -p "$work"
cp="$studio:$pg_classes:$mysql_classes:$gson"
mapfile -t sources < <(find "$repo_root/integration-tests/e2e" -name '*.java' -print | sort)
javac --release 21 -cp "$cp" -d "$work" "${sources[@]}"

TESTSTUDIO_DB_KIND=postgresql \
TESTSTUDIO_DB_URL='jdbc:postgresql://127.0.0.1:55433/teststudio' \
TESTSTUDIO_DB_USER=teststudio TESTSTUDIO_DB_PASSWORD=teststudio \
java -cp "$cp:$pg_driver:$work" org.jkiss.dbeaver.teststudio.e2e.DatabaseTransactionE2ETest

TESTSTUDIO_DB_KIND=mysql \
TESTSTUDIO_DB_URL='jdbc:mysql://127.0.0.1:53307/teststudio?useSSL=false&allowPublicKeyRetrieval=true' \
TESTSTUDIO_DB_USER=teststudio TESTSTUDIO_DB_PASSWORD=teststudio \
java -cp "$cp:$mysql_driver:$work" org.jkiss.dbeaver.teststudio.e2e.DatabaseTransactionE2ETest
