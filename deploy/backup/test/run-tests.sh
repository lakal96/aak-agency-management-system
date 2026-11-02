#!/usr/bin/env bash
# Tests for aak-backup.sh and restore-test.sh against a real local MySQL (the local stack:
# aak-agency-ops/local/aak-local.sh up) and a fake AWS CLI. No AWS account or Docker needed.
#   deploy/backup/test/run-tests.sh
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BK="$HERE/.."
MYSQL_BIN="${MYSQL_BIN_DIR:-$HOME/.local/opt/mysql-8.0/bin}"
ENVF="${AAK_LOCAL_ENV:-$HOME/.aak-local/env}"
[ -f "$ENVF" ] || { echo "start the local stack first (aak-local.sh init && up)"; exit 2; }
set -a; . "$ENVF"; set +a
export LD_LIBRARY_PATH="$HOME/.local/opt/libaio/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}"
SOCK="${MYSQL_SOCK:-$HOME/.aak-local/mysql/mysql.sock}"
MYSQL="$MYSQL_BIN/mysql --no-defaults -S $SOCK -uroot -p$MYSQL_ROOT_PASSWORD"
export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
export LIVE_DB="${LIVE_DB:-$MYSQL_DATABASE}"

pass=0; fail=0
check() { # <description> <command...>
  local d="$1"; shift
  if "$@" >/dev/null 2>&1; then echo "  PASS  $d"; pass=$((pass + 1)); else echo "  FAIL  $d"; fail=$((fail + 1)); fi
}
fresh() { T="$(mktemp -d)"; export FAKE_S3="$T/s3" FAKE_LOG="$T/metrics" BACKUP_DIR="$T/backups" UPLOADS_PATH="$T/uploads"
  mkdir -p "$FAKE_S3" "$UPLOADS_PATH"; : > "$FAKE_LOG"; echo "scanned CBL bill" > "$UPLOADS_PATH/cbl-1.png"
  export AWS="$HERE/fake-aws" BACKUP_BUCKET=test-bucket TODAY_DAY=15
  export DUMP_CMD="$MYSQL_BIN/mysqldump --no-defaults -S $SOCK -uroot --single-transaction --routines --triggers --no-tablespaces $LIVE_DB"; }
metric() { grep -c "^$1 $2\$" "$FAKE_LOG"; }
in_s3() { ls "$FAKE_S3"/test-bucket/$1* >/dev/null 2>&1; }

echo "== backup: happy path"
fresh
"$BK/aak-backup.sh" > "$T/out.log" 2>&1; rc=$?
check "exits 0" test "$rc" -eq 0
check "database dump is in S3 under daily/" in_s3 daily/aak-db-
check "uploaded CBL bills are in S3 under uploads/" in_s3 uploads/aak-uploads-
check "BackupSuccess=1 published" test "$(metric BackupSuccess 1)" -ge 1
check "no failure metric" test "$(metric BackupSuccess 0)" -eq 0
check "not copied to monthly/ on the 15th" bash -c "! ls $FAKE_S3/test-bucket/monthly/* 2>/dev/null"
check "no .partial file left behind" bash -c "! ls $BACKUP_DIR/*.partial 2>/dev/null"
check "backup folder is private (700)" test "$(stat -c %a "$BACKUP_DIR")" = 700

echo "== backup: first of the month keeps a monthly copy"
fresh; TODAY_DAY=01 "$BK/aak-backup.sh" > "$T/out.log" 2>&1
check "monthly/ copy exists" in_s3 monthly/aak-db-

echo "== backup: the dump command fails"
fresh; DUMP_CMD='echo "mysqldump: connection refused" >&2; exit 3' "$BK/aak-backup.sh" > "$T/out.log" 2>&1; rc=$?
check "exits non-zero" test "$rc" -ne 0
check "BackupSuccess=0 published" test "$(metric BackupSuccess 0)" -ge 1
check "nothing uploaded" bash -c "! ls $FAKE_S3/test-bucket/daily/* 2>/dev/null"

echo "== backup: dump is just a few bytes (empty / broken)"
fresh; DUMP_CMD='echo hello' "$BK/aak-backup.sh" > "$T/out.log" 2>&1; rc=$?
check "rejected (exit non-zero)" test "$rc" -ne 0
check "BackupSuccess=0 published" test "$(metric BackupSuccess 0)" -ge 1
check "nothing uploaded" bash -c "! ls $FAKE_S3/test-bucket/daily/* 2>/dev/null"

echo "== backup: dump is cut off before the end (no completion marker)"
fresh; DUMP_CMD="$DUMP_CMD | grep -v 'Dump completed'" "$BK/aak-backup.sh" > "$T/out.log" 2>&1; rc=$?
check "rejected as truncated" test "$rc" -ne 0
check "BackupSuccess=0 published" test "$(metric BackupSuccess 0)" -ge 1

echo "== backup: far fewer tables than expected"
fresh; MIN_TABLES=500 "$BK/aak-backup.sh" > "$T/out.log" 2>&1; rc=$?
check "rejected" test "$rc" -ne 0

echo "== backup: old local copies are tidied, recent ones kept"
fresh; mkdir -p "$BACKUP_DIR"; touch -d '10 days ago' "$BACKUP_DIR/aak-db-old.sql.gz"; touch -d '1 day ago' "$BACKUP_DIR/aak-db-recent.sql.gz"; touch -d '10 days ago' "$BACKUP_DIR/pre-hardening-manual.sql.gz"
"$BK/aak-backup.sh" > "$T/out.log" 2>&1
check "10-day-old backup removed" test ! -e "$BACKUP_DIR/aak-db-old.sql.gz"
check "1-day-old backup kept" test -e "$BACKUP_DIR/aak-db-recent.sql.gz"
check "a manual dump with another name is never touched" test -e "$BACKUP_DIR/pre-hardening-manual.sql.gz"

echo "== restore test: restores the latest backup and matches live"
fresh; "$BK/aak-backup.sh" > "$T/out.log" 2>&1
RESTORE_MODE=local LOCAL_MYSQL="$MYSQL" "$BK/restore-test.sh" > "$T/restore.log" 2>&1; rc=$?
check "exits 0" test "$rc" -eq 0
check "RestoreTestSuccess=1 published" test "$(metric RestoreTestSuccess 1)" -ge 1
check "reports the restored tables" grep -q "restore test PASSED" "$T/restore.log"
check "scratch database was dropped" bash -c "! $MYSQL -N -e 'SHOW DATABASES' | grep -q '^restoretest_'"

echo "== restore test: the backup in S3 is corrupt"
fresh; "$BK/aak-backup.sh" > "$T/out.log" 2>&1
newest=$(ls -1t "$FAKE_S3"/test-bucket/daily/* | head -n 1); echo "garbage, not gzip" > "$newest"
RESTORE_MODE=local LOCAL_MYSQL="$MYSQL" "$BK/restore-test.sh" > "$T/restore.log" 2>&1; rc=$?
check "exits non-zero" test "$rc" -ne 0
check "RestoreTestSuccess=0 published" test "$(metric RestoreTestSuccess 0)" -ge 1

echo "== restore test: there is no backup at all"
fresh
RESTORE_MODE=local LOCAL_MYSQL="$MYSQL" "$BK/restore-test.sh" > "$T/restore.log" 2>&1; rc=$?
check "exits non-zero" test "$rc" -ne 0
check "RestoreTestSuccess=0 published" test "$(metric RestoreTestSuccess 0)" -ge 1

echo "== restore test: the temporary MySQL container is removed together with its anonymous data volume"
check "every docker rm in restore-test.sh uses -v (otherwise ~200 MB leaks per run)" bash -c "! grep -E 'docker rm' $BK/restore-test.sh | grep -v -E 'docker rm -fv|^#'"
check "...and there is at least one" test "$(grep -c 'docker rm -fv' "$BK/restore-test.sh")" -ge 2

echo; echo "passed: $pass   failed: $fail"
[ "$fail" -eq 0 ]
