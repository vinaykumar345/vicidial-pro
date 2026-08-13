# Vicidial Go-Live Execution Results (2026-08-13)

Scope: Automated and infrastructure-level checks executed on local production stack.

## Summary

- PASS: Core stack health, HTTPS access, DB initialization, DB backup job, daemon supervision, Asterisk service health.
- PARTIAL: Telephony provisioning templates loaded but still contain carrier placeholders.
- PENDING: Real trunk registration, real inbound/outbound call validation, security control verification from external networks.

## Detailed Results

1. Service health gate: PASS
- All services running and healthy in production compose status.

2. HTTPS endpoint gate: PASS
- HTTPS response for /agc/vicidial.php returned 200.

3. Vicidial daemon gate: PASS
- Daemon container healthy and keepalive loop active.

4. Backup gate: PASS
- Backup sidecar created dump file in /backups:
  - asterisk-20260813-124806.sql.gz

5. Database schema gate: PASS
- Vicidial schema tables present in asterisk database.

6. Database permissions gate: PASS (fixed during run)
- Granted asterisk schema privileges to cron user.
- Verified cron user can query schema.

7. Asterisk transport/driver gate: PASS
- Asterisk image is PJSIP-capable.
- PJSIP endpoints and registration objects are loaded.

8. Telephony carrier configuration gate: FAIL (expected before carrier handoff)
- Placeholder values still present in [docker/asterisk/etc/pjsip.conf](docker/asterisk/etc/pjsip.conf):
  - YOUR_CARRIER_USERNAME
  - YOUR_CARRIER_PASSWORD
  - sip.carrier.example
  - 203.0.113.10

9. Real SIP registration gate: FAIL (expected)
- Current registration object state is Unregistered.

10. End-to-end call-flow QA gate: PENDING
- Requires real trunk credentials and external test numbers.

## Changes Applied During Validation

- Added PJSIP production template file: [docker/asterisk/etc/pjsip.conf](docker/asterisk/etc/pjsip.conf)
- Mounted PJSIP config in production compose: [docker-compose.prod.yml](docker-compose.prod.yml)
- Updated checklist for PJSIP commands and examples: [GO_LIVE_CHECKLIST.md](GO_LIVE_CHECKLIST.md)
- Replaced legacy placeholder in sip template: [docker/asterisk/etc/sip.conf](docker/asterisk/etc/sip.conf)
- Fixed DB grants for cron user in running DB.

## Remaining Actions Before Cutover

1. Replace all carrier placeholders in [docker/asterisk/etc/pjsip.conf](docker/asterisk/etc/pjsip.conf).
2. Reload Asterisk and confirm registration becomes Registered.
3. Run the full call-flow QA matrix in [GO_LIVE_CHECKLIST.md](GO_LIVE_CHECKLIST.md).
4. Validate AMI and SIP firewall restrictions from outside trusted CIDRs.
5. Execute backup restore drill into a staging database and confirm integrity.
