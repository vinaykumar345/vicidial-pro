#!/usr/bin/env bash
set -euo pipefail

mkdir -p /var/log/astguiclient

cat > /etc/astguiclient.conf <<EOF
PATHhome=/opt/vicidial
PATHlogs=/var/log/astguiclient
PATHagi=/opt/vicidial/agi
PATHweb=/var/www/html
PATHsounds=/opt/vicidial/sounds
VARserver_ip=${VICIDIAL_SERVER_IP:-127.0.0.1}
VARDB_server=${VICIDIAL_DB_HOST:-vicidial-db}
VARDB_database=${VICIDIAL_DB_NAME:-asterisk}
VARDB_user=${VICIDIAL_DB_USER:-cron}
VARDB_pass=${VICIDIAL_DB_PASS:-vicidial}
VARDB_port=${VICIDIAL_DB_PORT:-3306}
VARAkeepalive_conf=/etc/asterisk/manager.conf
VARactive_keepalives=123456789
VARsend_email=0
EOF

exec docker-php-entrypoint "$@"
