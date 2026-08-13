#!/usr/bin/env bash
set -euo pipefail

DB_HOST="${VICIDIAL_DB_HOST:-vicidial-db}"
DB_PORT="${VICIDIAL_DB_PORT:-3306}"

until nc -z "$DB_HOST" "$DB_PORT"; do
  echo "Waiting for database at $DB_HOST:$DB_PORT"
  sleep 2
done

echo "Starting Vicidial keepalive daemons"
while true; do
  perl /opt/vicidial/bin/ADMIN_keepalive_ALL.pl --debugX || true
  echo "Vicidial keepalive exited, restarting in 3 seconds"
  sleep 3
done
