#!/usr/bin/env bash
set -euo pipefail

DB_HOST="${VICIDIAL_DB_HOST:-vicidial-db}"
DB_PORT="${VICIDIAL_DB_PORT:-3306}"
DB_NAME="${VICIDIAL_DB_NAME:-asterisk}"
DB_USER="${VICIDIAL_DB_USER:-cron}"
DB_PASS="${VICIDIAL_DB_PASS:-vicidial}"
INTERVAL="${BACKUP_INTERVAL_SECONDS:-21600}"

mkdir -p /backups

while true; do
  ts="$(date +%Y%m%d-%H%M%S)"
  outfile="/backups/${DB_NAME}-${ts}.sql.gz"
  echo "Creating backup: $outfile"
  mysqldump -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASS" "$DB_NAME" | gzip > "$outfile"
  find /backups -type f -name '*.sql.gz' -mtime +7 -delete
  sleep "$INTERVAL"
done
