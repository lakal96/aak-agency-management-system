# Backups

What is protected, and how to get it back.

| What | How often | Where | Kept for |
|---|---|---|---|
| Database dump (every shop, bill, payment, stock movement, user...) | every night 02:30 (Sri Lanka) | `s3://aak-backups-<account>/daily/` | 35 days |
| First dump of each month | 1st of the month | `.../monthly/` | 13 months |
| Uploaded original CBL bills (the proof behind each purchase invoice) | every night | `.../uploads/` | 35 days |
| Whole server disk (snapshot) | every night ~02:00 | EBS snapshots (AWS Data Lifecycle Manager) | 7 days |
| Copy on the server itself | every night | `/opt/aak-agency/backups/` | 7 days |

**Worst case loss:** about one day of entries (the work since the last nightly backup). The bucket is private,
encrypted, versioned, and the server can *add* backups but not delete them, so a hacked server cannot erase its own history.

## It checks itself
* `aak-backup.sh` refuses to call a dump good unless it is a complete gzip, large enough, has the expected tables
  and ends with mysqldump's "Dump completed" line, and the S3 copy has exactly the local size.
* Every Wednesday and Sunday 03:30 `restore-test.sh` restores the newest backup into a throw-away MySQL and compares it with the
  live database (same tables, sensible row counts). A backup that cannot be restored is reported as a failure.
* Both publish a CloudWatch metric. An alarm emails the owner if a night's backup is **missing or failed**
  (which also covers "the server was down" or "the timer stopped"), if the restore test has not passed for a
  week, if the server fails its health check, or if the disk is over 80% full.

On the server: `systemctl list-timers 'aak-*'`, `journalctl -u aak-backup.service -n 50`,
`sudo systemctl start aak-backup.service` (run one now), `sudo systemctl start aak-restore-test.service`.

## Restoring for real
Do this with the Owner's approval; it replaces the live data with the backup.

1. **Pick the backup** and download it to the server:
   ```bash
   aws s3 ls s3://aak-backups-<account>/daily/ | tail
   aws s3 cp s3://aak-backups-<account>/daily/aak-db-<stamp>.sql.gz /tmp/restore.sql.gz
   gzip -t /tmp/restore.sql.gz
   ```
2. **Safety copy of what is there now**, then stop the app so nothing writes during the restore:
   ```bash
   sudo /opt/aak-agency/backup/aak-backup.sh        # or take a manual mysqldump
   docker compose -f /opt/aak-agency/docker-compose.yml stop app
   ```
3. **Replace the database** (the app's MySQL user keeps its rights on the recreated database):
   ```bash
   docker exec aak-agency-mysql-1 sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE aak_agency_db; CREATE DATABASE aak_agency_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"'
   zcat /tmp/restore.sql.gz | docker exec -i aak-agency-mysql-1 sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" aak_agency_db'
   ```
4. **Bring the app back** and check it: `docker compose -f /opt/aak-agency/docker-compose.yml start app`, then
   `curl https://aakagency.work.gd/actuator/health` and sign in.
5. **Original CBL bills** (only if lost): download `uploads/aak-uploads-<stamp>.tar.gz` and
   `tar -xzf` it into `/var/lib/docker/volumes/aak-agency_uploads_data/_data/`.

If the whole server is lost, create a volume from the latest EBS snapshot (or rebuild with Terraform and restore the
dump above onto the fresh host).

## Tests
`deploy/backup/test/run-tests.sh` runs both scripts against the local stack's MySQL with a fake AWS CLI, including the
failure cases (failed dump, empty dump, truncated dump, corrupt backup, no backup). See the file header.
