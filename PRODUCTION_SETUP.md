# Vicidial Production Setup

This repository now includes a production-oriented Docker stack for Vicidial with these services:

- `vicidial-web` (legacy PHP runtime compatible with Vicidial code)
- `vicidial-db` (MariaDB with persistent storage and init SQL)
- `vicidial-asterisk` (Asterisk service with configurable AMI credentials)
- `vicidial-daemons` (Vicidial keepalive daemon runner)
- `vicidial-proxy` (Nginx TLS reverse proxy)
- `vicidial-db-backup` (scheduled compressed DB backups)

## 1) Prepare environment

```bash
cp .env.production.example .env.production
```

Edit `.env.production` and set strong secrets.

## 2) Provide TLS certificates

Create certificate files:

- `secrets/tls/fullchain.pem`
- `secrets/tls/privkey.pem`

For testing only, self-signed certs can be generated:

```bash
mkdir -p secrets/tls
openssl req -x509 -newkey rsa:4096 -sha256 -days 365 -nodes \
  -keyout secrets/tls/privkey.pem \
  -out secrets/tls/fullchain.pem \
  -subj "/CN=localhost"
```

## 3) Build and start production stack

```bash
docker compose -f docker-compose.prod.yml --env-file .env.production up --build -d
```

## 4) Validate runtime

```bash
docker compose -f docker-compose.prod.yml ps
curl -k -I https://localhost/agc/vicidial.php
```

## 5) Access application

- HTTPS URL: `https://<your-domain>/agc/vicidial.php`
- Admin URL: `https://<your-domain>/vicidial/admin.php`

## 6) Operational commands

Show logs:

```bash
docker compose -f docker-compose.prod.yml logs -f vicidial-web vicidial-db vicidial-asterisk vicidial-daemons
```

Stop stack:

```bash
docker compose -f docker-compose.prod.yml down
```

Backups are written to volume `vicidial_db_backups_prod`.

## 7) Production hardening checklist

- Replace all placeholder passwords in `.env.production` and SIP config
- Lock down host firewall to required ports only: `80`, `443`, `5060`, RTP range, `5038` (if remote AMI needed)
- Restrict AMI (`5038`) by source IP or remove public exposure
- Configure real SIP trunks and dialplan in `docker/asterisk/etc/`
- Configure DNS and valid public TLS certificates
- Set up off-host backup replication for DB dumps
- Add centralized logging/monitoring (Prometheus/Grafana/ELK)
- Run failover/restore tests before go-live

## Notes

- Vicidial is a legacy stack and requires legacy-compatible runtime behavior.
- Telephony readiness depends on SIP trunk/carrier settings and Asterisk dialplan customization.
- For production cutover validation, use `GO_LIVE_CHECKLIST.md`.
