#!/usr/bin/env bash
set -euo pipefail

DB_HOST="${VICIDIAL_DB_HOST:-vicidial-db}"
DB_PORT="${VICIDIAL_DB_PORT:-3306}"
DB_NAME="${VICIDIAL_DB_NAME:?VICIDIAL_DB_NAME is required}"
DB_USER="${VICIDIAL_DB_USER:?VICIDIAL_DB_USER is required}"
DB_PASS="${VICIDIAL_DB_PASS:?VICIDIAL_DB_PASS is required}"

API_USER="${VICIDIAL_API_USER:-6666}"
API_PASS="${VICIDIAL_API_PASS:-1234}"
DEFAULT_AGENT_USER="${VICIDIAL_DEFAULT_AGENT_USER:-9885976256}"
DEFAULT_CAMPAIGN="${VICIDIAL_DEFAULT_CAMPAIGN:-TESTCAMP}"
DEFAULT_SERVER_IP="${VICIDIAL_ACTIVE_SERVER_IP:-10.10.10.15}"
DEFAULT_PHONE_PASS="${VICIDIAL_DEFAULT_PHONE_PASS:-${DEFAULT_AGENT_USER}}"

MYSQL_CMD=(mysql -h "${DB_HOST}" -P "${DB_PORT}" -u"${DB_USER}" -p"${DB_PASS}" "${DB_NAME}")

echo "[vicidial-bootstrap] Waiting for database..."
for _ in {1..60}; do
  if "${MYSQL_CMD[@]}" -e "SELECT 1;" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

echo "[vicidial-bootstrap] Applying idempotent Vicidial fixes..."
"${MYSQL_CMD[@]}" <<SQL
ALTER TABLE vicidial_campaigns
  ADD COLUMN IF NOT EXISTS end_call_action VARCHAR(20) DEFAULT 'NONE';

UPDATE system_settings
SET vdc_agent_api_active='1';

UPDATE vicidial_users
SET vdc_agent_api_access='1'
WHERE user='${API_USER}';

UPDATE vicidial_users
SET pass='${API_PASS}'
WHERE user='${API_USER}' AND (pass IS NULL OR pass = '' OR pass <> '${API_PASS}');

UPDATE vicidial_campaigns
SET no_hopper_leads_logins='Y'
WHERE campaign_id='${DEFAULT_CAMPAIGN}';

UPDATE vicidial_users
SET agentcall_manual='1',
    phone_login='${DEFAULT_AGENT_USER}',
    phone_pass='${DEFAULT_PHONE_PASS}'
WHERE user='${DEFAULT_AGENT_USER}';

INSERT INTO phones (
  extension,
  dialplan_number,
  voicemail_id,
  server_ip,
  login,
  pass,
  status,
  active,
  protocol,
  fullname,
  local_gmt,
  template_id,
  conf_secret,
  phone_context
) VALUES (
  '${DEFAULT_AGENT_USER}',
  '${DEFAULT_AGENT_USER}',
  '${DEFAULT_AGENT_USER}',
  '${DEFAULT_SERVER_IP}',
  '${DEFAULT_AGENT_USER}',
  '${DEFAULT_PHONE_PASS}',
  'ACTIVE',
  'Y',
  'EXTERNAL',
  'SIM Agent ${DEFAULT_AGENT_USER}',
  '5.5',
  'test',
  'test',
  'default'
)
ON DUPLICATE KEY UPDATE
  dialplan_number = VALUES(dialplan_number),
  voicemail_id = VALUES(voicemail_id),
  server_ip = VALUES(server_ip),
  login = VALUES(login),
  pass = VALUES(pass),
  status = VALUES(status),
  active = VALUES(active),
  protocol = VALUES(protocol),
  fullname = VALUES(fullname),
  local_gmt = VALUES(local_gmt),
  template_id = VALUES(template_id),
  conf_secret = VALUES(conf_secret),
  phone_context = VALUES(phone_context);
SQL

echo "[vicidial-bootstrap] Done."