# Vicidial Go-Live Checklist

Use this checklist to validate the full Vicidial stack before switching production traffic.

## 1) Environment Readiness

- [ ] Host sizing confirmed (CPU, RAM, disk IOPS) for expected concurrent agents/calls.
- [ ] Time sync configured (`chrony` or `ntp`) on host and verified in UTC.
- [ ] Static public IP assigned and DNS records created (`A`/`AAAA`).
- [ ] Firewall policy applied (deny by default).
- [ ] Required ports opened only to trusted sources:
  - [ ] `80/tcp`, `443/tcp` (public web)
  - [ ] `5060/udp`, `5060/tcp` (SIP signaling)
  - [ ] `10000-10100/udp` (RTP media)
  - [ ] `5038/tcp` (AMI) restricted to internal/admin IP ranges only

## 2) Secrets and Config

- [ ] `.env.production` populated with strong credentials.
- [ ] No default/placeholder passwords remain in:
  - [ ] [vicidial-src/.env.production](vicidial-src/.env.production)
  - [ ] [vicidial-src/docker/asterisk/etc/sip.conf](vicidial-src/docker/asterisk/etc/sip.conf)
- [ ] TLS cert/key installed in:
  - [ ] [vicidial-src/secrets/tls/fullchain.pem](vicidial-src/secrets/tls/fullchain.pem)
  - [ ] [vicidial-src/secrets/tls/privkey.pem](vicidial-src/secrets/tls/privkey.pem)
- [ ] AMI credentials aligned between Vicidial and Asterisk.

## 3) SIP Trunk Provisioning

Record these values from your carrier:

- Carrier name: ____________________
- SIP registrar/proxy: ____________________
- Auth username: ____________________
- Auth password: ____________________
- DID list/ranges: ____________________
- Codec policy: ____________________
- IP allowlist/CIDR: ____________________

Configure trunk in [vicidial-src/docker/asterisk/etc/pjsip.conf](vicidial-src/docker/asterisk/etc/pjsip.conf):

```ini
[carrier-auth]
type=auth
auth_type=userpass
username=YOUR_CARRIER_USERNAME
password=YOUR_CARRIER_PASSWORD

[carrier-aor]
type=aor
contact=sip:sip.carrier.example

[carrier-endpoint]
type=endpoint
context=from-carrier
transport=transport-udp
disallow=all
allow=ulaw,alaw
outbound_auth=carrier-auth
aors=carrier-aor

[carrier-registration]
type=registration
transport=transport-udp
outbound_auth=carrier-auth
server_uri=sip:sip.carrier.example
client_uri=sip:YOUR_CARRIER_USERNAME@sip.carrier.example
```

Inbound registration example (if required by carrier):

```ini
[carrier-identify]
type=identify
endpoint=carrier-endpoint
match=203.0.113.10
```

## 4) Dialplan Baseline

Update [vicidial-src/docker/asterisk/etc/extensions.conf](vicidial-src/docker/asterisk/etc/extensions.conf):

```ini
[from-carrier]
exten => YOUR_DID,1,NoOp(Inbound DID ${EXTEN})
 same => n,Goto(vicidial,${EXTEN},1)

[from-internal]
exten => _X.,1,NoOp(Outbound call ${EXTEN})
 same => n,Dial(SIP/carrier-trunk/${EXTEN},60)
 same => n,Hangup()
```

Dialplan validation commands:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.production exec vicidial-asterisk asterisk -rx "dialplan reload"
docker compose -f docker-compose.prod.yml --env-file .env.production exec vicidial-asterisk asterisk -rx "dialplan show from-carrier"
docker compose -f docker-compose.prod.yml --env-file .env.production exec vicidial-asterisk asterisk -rx "pjsip show endpoints"
docker compose -f docker-compose.prod.yml --env-file .env.production exec vicidial-asterisk asterisk -rx "pjsip show registrations"
```

## 5) Database and Backup Validation

- [ ] DB schema initialized successfully.
- [ ] `vicidial-db-backup` container running.
- [ ] At least one backup file exists in backup volume.
- [ ] Restore test performed to a staging DB.

Commands:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.production logs --tail=100 vicidial-db
docker compose -f docker-compose.prod.yml --env-file .env.production logs --tail=100 vicidial-db-backup
docker volume inspect vicidial-src_vicidial_db_backups_prod
```

## 6) Service Health Gates

- [ ] All services are `Up` and healthy.
- [ ] HTTPS endpoint returns `200`.
- [ ] Vicidial daemon service reports keepalive loop running.

Commands:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.production ps
curl -k -s -o /dev/null -w "%{http_code}\n" https://localhost/agc/vicidial.php
docker compose -f docker-compose.prod.yml --env-file .env.production logs --tail=120 vicidial-daemons
```

## 7) QA Call-Flow Test Matrix

Run these tests with two softphones and one external number.

1. Agent Login
- Expected: valid agent can log in; invalid credentials rejected.
- Evidence: screenshot or log entry.

2. Manual Outbound Call
- Action: place manual call to test number.
- Expected: call setup < 5 sec; two-way audio works.

3. Predictive/Auto Dial Cycle
- Action: enable campaign dialing with test leads.
- Expected: call pacing starts, agent receives connected calls.

4. Inbound DID Routing
- Action: call each published DID.
- Expected: routes to intended Vicidial queue/campaign.

5. DTMF Capture
- Action: send DTMF in IVR path.
- Expected: digits recognized correctly.

6. Recording Verification
- Action: complete a call with recording enabled.
- Expected: recording appears and is playable.

7. Transfer Scenarios
- Action: blind transfer and attended transfer.
- Expected: successful transfer and clean hangup states.

8. Failover Behavior
- Action: restart `vicidial-asterisk` during test window.
- Expected: service recovers; new calls resume automatically.

9. Backup and Restore Drill
- Action: trigger/verify latest dump and restore in staging.
- Expected: restored data integrity checks pass.

10. Security Regression
- Action: attempt unauthorized AMI access from blocked IP.
- Expected: connection denied and event logged.

## 8) Cutover Plan

1. Freeze config changes for 24 hours before cutover.
2. Lower DNS TTL ahead of switch.
3. Execute smoke test (agent login, one inbound, one outbound).
4. Route small percentage of live traffic first.
5. Monitor ASR, ACD, dropped call %, SIP error rates for 60 minutes.
6. Expand to full traffic only if all KPIs within thresholds.

## 9) Rollback Plan

Keep rollback ready before cutover:

- [ ] Previous routing/trunk config snapshot saved.
- [ ] Prior DNS/LB config documented.
- [ ] `docker compose` rollback command prepared.
- [ ] On-call owner and escalation contacts listed.

Rollback trigger examples:

- Sustained call failure rate > 5%
- No-audio issues on > 3 consecutive test calls
- Agent login/auth failure trend

## 10) Sign-Off

- Operations Lead: ____________________ Date: __________
- Voice Engineer: _____________________ Date: __________
- Security Reviewer: __________________ Date: __________
- Product Owner: ______________________ Date: __________
