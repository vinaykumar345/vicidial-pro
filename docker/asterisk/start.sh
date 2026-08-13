#!/usr/bin/env bash
set -euo pipefail

AMI_USER="${VICIDIAL_AMI_USER:-cron}"
AMI_PASS="${VICIDIAL_AMI_PASS:-vicidial-ami-pass}"

cat > /etc/asterisk/manager.conf <<EOF
[general]
enabled = yes
webenabled = no
port = 5038
bindaddr = 0.0.0.0

[${AMI_USER}]
secret = ${AMI_PASS}
read = all
write = all
EOF

exec /usr/sbin/asterisk -fvvv
