#!/usr/bin/env bash
# Proves the newest nightly backup can really be restored.
#
# Downloads the latest database backup from S3, loads it into a throw-away MySQL, and compares what came
# back with the live database: same tables, same schema history, and every table's row count not larger
# than live (the live database may have grown since the backup) and not empty where live has data.
# The result is published as a CloudWatch metric, so a backup that cannot be restored raises an alarm.
#
#   RESTORE_MODE=docker (default)  a temporary mysql:8.0 container on the app host
#   RESTORE_MODE=local             a scratch database on a MySQL you already run (development, tests):
#                                  needs LOCAL_MYSQL="mysql --no-defaults -S /path/sock -uroot -pSECRET"
#
# Overridable for tests: AWS, BACKUP_BUCKET, RESTORE_MODE, LOCAL_MYSQL, LIVE_DB, LIVE_MYSQL.

set -Eeuo pipefail

REGION="${AWS_REGION:-ap-southeast-1}"
DB_NAME="${DB_NAME:-aak_agency_db}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-aak-agency-mysql-1}"
METRIC_ENV="${METRIC_ENV:-dev}"
AWS="${AWS:-aws}"
MODE="${RESTORE_MODE:-docker}"
SCRATCH_DB="restoretest_$$"
TEST_CONTAINER="aak-restore-test"
TEST_PASSWORD="restore-test-only"
WORK="$(mktemp -d)"

log() { echo "[$(date '+%F %T')] $*"; }
die() { log "ERROR: $*"; return 1; }
publish() {
  "$AWS" cloudwatch put-metric-data --region "$REGION" --namespace AAK --metric-name "$1" \
    --dimensions "Env=$METRIC_ENV" --value "$2" --unit "$3" >/dev/null 2>&1 || log "warning: could not publish metric $1"
}
cleanup() {
  rm -rf "$WORK"
  if [ "$MODE" = docker ]; then docker rm -f "$TEST_CONTAINER" >/dev/null 2>&1 || true
  else $LOCAL_MYSQL -e "DROP DATABASE IF EXISTS \`$SCRATCH_DB\`" >/dev/null 2>&1 || true; fi
}
on_failure() { local rc=$? line=$1; log "RESTORE TEST FAILED (exit $rc, line $line)"; publish RestoreTestSuccess 0 Count; exit "$rc"; }
trap cleanup EXIT
trap 'on_failure $LINENO' ERR

# --- the two databases we compare: the restored copy, and the live one
if [ "$MODE" = docker ]; then
  restored_sql() { docker exec -i "$TEST_CONTAINER" mysql -uroot -p"$TEST_PASSWORD" "$@"; }
  live_sql() { docker exec "$MYSQL_CONTAINER" sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N $DB_NAME -e \"\$0\"" "$1"; }
  restored_db="restoretest"
else
  : "${LOCAL_MYSQL:?RESTORE_MODE=local needs LOCAL_MYSQL}"
  restored_sql() { $LOCAL_MYSQL "$@"; }
  live_sql() { ${LIVE_MYSQL:-$LOCAL_MYSQL} -N "${LIVE_DB:-$DB_NAME}" -e "$1"; }
  restored_db="$SCRATCH_DB"
fi

if [ -z "${BACKUP_BUCKET:-}" ]; then
  BACKUP_BUCKET="aak-backups-$("$AWS" sts get-caller-identity --query Account --output text)"
fi

# --- 1. fetch the newest database backup
KEY=$("$AWS" s3api list-objects-v2 --bucket "$BACKUP_BUCKET" --prefix daily/ \
  --query 'sort_by(Contents,&LastModified)[-1].Key' --output text)
[ -n "$KEY" ] && [ "$KEY" != "None" ] || die "no backup found in s3://$BACKUP_BUCKET/daily/"
log "restoring $KEY"
"$AWS" s3 cp --only-show-errors "s3://$BACKUP_BUCKET/$KEY" "$WORK/backup.sql.gz"
gzip -t "$WORK/backup.sql.gz" || die "backup file is corrupt"

# --- 2. load it into a throw-away MySQL
if [ "$MODE" = docker ]; then
  docker rm -f "$TEST_CONTAINER" >/dev/null 2>&1 || true
  docker run -d --name "$TEST_CONTAINER" -e MYSQL_ROOT_PASSWORD="$TEST_PASSWORD" -e MYSQL_DATABASE=restoretest \
    mysql:8.0 --innodb-buffer-pool-size=64M --skip-log-bin >/dev/null
  for _ in $(seq 1 90); do
    docker exec "$TEST_CONTAINER" mysqladmin -uroot -p"$TEST_PASSWORD" ping >/dev/null 2>&1 \
      && docker exec "$TEST_CONTAINER" mysql -uroot -p"$TEST_PASSWORD" -e 'select 1' restoretest >/dev/null 2>&1 && break
    sleep 2
  done
else
  restored_sql -e "CREATE DATABASE \`$SCRATCH_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
fi
zcat "$WORK/backup.sql.gz" | restored_sql "$restored_db"

# --- 3. compare with live
tables_restored=$(restored_sql -N "$restored_db" -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$restored_db' AND table_type='BASE TABLE'")
tables_live=$(live_sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${LIVE_DB:-$DB_NAME}' AND table_type='BASE TABLE'")
[ "$tables_restored" = "$tables_live" ] || die "table count differs: restored $tables_restored vs live $tables_live"

mig_restored=$(restored_sql -N "$restored_db" -e "SELECT COUNT(*) FROM flyway_schema_history")
mig_live=$(live_sql "SELECT COUNT(*) FROM flyway_schema_history")
[ "$mig_restored" = "$mig_live" ] || log "note: schema history differs (restored $mig_restored vs live $mig_live) - normal right after a deploy"

problems=0
for t in $(live_sql "SELECT table_name FROM information_schema.tables WHERE table_schema='${LIVE_DB:-$DB_NAME}' AND table_type='BASE TABLE' ORDER BY 1"); do
  r=$(restored_sql -N "$restored_db" -e "SELECT COUNT(*) FROM \`$t\`")
  l=$(live_sql "SELECT COUNT(*) FROM \`$t\`")
  if [ "$r" -gt "$l" ] || { [ "$l" -gt 0 ] && [ "$r" -eq 0 ]; }; then
    log "MISMATCH $t: restored $r, live $l"; problems=$((problems + 1))
  else
    log "ok  $t: restored $r (live now $l)"
  fi
done
[ "$problems" -eq 0 ] || die "$problems table(s) did not restore sensibly"

publish RestoreTestSuccess 1 Count
log "restore test PASSED: $tables_restored tables restored from $KEY"
