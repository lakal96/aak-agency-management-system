#!/usr/bin/env bash
# Nightly backup of the AAK database and the uploaded CBL invoice files, copied to S3.
#
# Runs on the app host from a systemd timer (aak-backup.timer). It only ever ADDS backups: the host's
# IAM role may put objects but not delete them, and old ones expire through the bucket's lifecycle rules,
# so a compromised server cannot wipe its own backups.
#
# Safety checks before anything is called a success:
#   * the dump must be a complete gzip, big enough, contain the expected tables, and end with
#     mysqldump's "Dump completed" marker (a truncated dump has none);
#   * the object in S3 must have exactly the size of the local file.
# Success or failure is published as a CloudWatch metric; a CloudWatch alarm emails the owner when a
# night's success is missing (so a host that is down or a timer that stopped is noticed too).
#
# Overridable for tests: AWS, DUMP_CMD, UPLOADS_PATH, BACKUP_DIR, BACKUP_BUCKET, MIN_TABLES, TODAY_DAY.

set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/aak-agency}"
BACKUP_DIR="${BACKUP_DIR:-$APP_DIR/backups}"
REGION="${AWS_REGION:-ap-southeast-1}"
DB_NAME="${DB_NAME:-aak_agency_db}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-aak-agency-mysql-1}"
UPLOADS_PATH="${UPLOADS_PATH:-/var/lib/docker/volumes/aak-agency_uploads_data/_data}"
KEEP_LOCAL_DAYS="${KEEP_LOCAL_DAYS:-7}"
MIN_TABLES="${MIN_TABLES:-20}"
METRIC_ENV="${METRIC_ENV:-dev}"
AWS="${AWS:-aws}"
TODAY_DAY="${TODAY_DAY:-$(date +%d)}"
STAMP="$(date +%Y%m%d-%H%M%S)"

log() { echo "[$(date '+%F %T')] $*"; }
die() { log "ERROR: $*"; return 1; }

publish() { # <metric> <value> <unit>   - never lets a monitoring hiccup fail the backup itself
  "$AWS" cloudwatch put-metric-data --region "$REGION" --namespace AAK --metric-name "$1" \
    --dimensions "Env=$METRIC_ENV" --value "$2" --unit "$3" >/dev/null 2>&1 \
    || log "warning: could not publish metric $1"
}

on_failure() {
  local rc=$? line=$1
  log "BACKUP FAILED (exit $rc, line $line)"
  publish BackupSuccess 0 Count
  exit "$rc"
}
trap 'on_failure $LINENO' ERR

default_dump() {
  # The root password lives inside the MySQL container's environment, never on this command line.
  docker exec "$MYSQL_CONTAINER" sh -c \
    "exec mysqldump -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --single-transaction --routines --triggers --no-tablespaces $DB_NAME"
}

run_dump() { if [ -n "${DUMP_CMD:-}" ]; then bash -c "$DUMP_CMD"; else default_dump; fi; }

verify_dump() { # <file>
  local file="$1" size tables
  gzip -t "$file" || die "dump is not a valid gzip file"
  size=$(stat -c %s "$file")
  [ "$size" -gt 2048 ] || die "dump is suspiciously small ($size bytes)"
  tables=$(zcat "$file" | grep -c '^CREATE TABLE' || true)
  [ "$tables" -ge "$MIN_TABLES" ] || die "dump has only $tables tables (expected at least $MIN_TABLES)"
  zcat "$file" | tail -n 5 | grep -q 'Dump completed' || die "dump has no completion marker (truncated?)"
  log "dump verified: $size bytes, $tables tables"
}

upload() { # <file> <key>
  local file="$1" key="$2" remote
  "$AWS" s3 cp --only-show-errors --sse AES256 "$file" "s3://$BUCKET/$key"
  remote=$("$AWS" s3api head-object --bucket "$BUCKET" --key "$key" --query ContentLength --output text)
  [ "$remote" = "$(stat -c %s "$file")" ] || die "uploaded size ($remote) differs from local size for $key"
  log "uploaded s3://$BUCKET/$key ($remote bytes)"
}

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

if [ -z "${BACKUP_BUCKET:-}" ]; then
  ACCOUNT=$("$AWS" sts get-caller-identity --query Account --output text)
  BACKUP_BUCKET="aak-backups-$ACCOUNT"
fi
BUCKET="$BACKUP_BUCKET"
log "backup starting -> s3://$BUCKET"

# --- 1. database
DUMP_FILE="$BACKUP_DIR/aak-db-$STAMP.sql.gz"
PARTIAL="$DUMP_FILE.partial"
if ! { run_dump | gzip -9 > "$PARTIAL"; }; then
  rm -f "$PARTIAL"
  die "database dump failed"
fi
verify_dump "$PARTIAL"
mv "$PARTIAL" "$DUMP_FILE"
upload "$DUMP_FILE" "daily/$(basename "$DUMP_FILE")"

# --- 2. uploaded original CBL invoice files (small, but they are the proof behind every purchase invoice)
if [ -d "$UPLOADS_PATH" ]; then
  UPLOADS_FILE="$BACKUP_DIR/aak-uploads-$STAMP.tar.gz"
  tar -czf "$UPLOADS_FILE" -C "$UPLOADS_PATH" .
  tar -tzf "$UPLOADS_FILE" >/dev/null || die "uploads archive is unreadable"
  upload "$UPLOADS_FILE" "uploads/$(basename "$UPLOADS_FILE")"
else
  log "no uploads folder at $UPLOADS_PATH - skipping"
fi

# --- 3. keep the first backup of each month for a year (server-side copy)
if [ "$TODAY_DAY" = "01" ]; then
  "$AWS" s3 cp --only-show-errors --sse AES256 "s3://$BUCKET/daily/$(basename "$DUMP_FILE")" "s3://$BUCKET/monthly/$(basename "$DUMP_FILE")"
  log "kept as the monthly backup"
fi

# --- 4. tidy the local copies (S3 keeps the long history)
find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'aak-db-*' -o -name 'aak-uploads-*' \) -mtime +"$KEEP_LOCAL_DAYS" -delete

# --- 5. report
publish BackupSuccess 1 Count
publish BackupDumpBytes "$(stat -c %s "$DUMP_FILE")" Bytes
publish DiskUsedPercent "$(df --output=pcent / | tail -n 1 | tr -dc '0-9')" Percent
log "backup finished OK"
