# Vicidial Docker Build Guide

This repository does not include an official Docker setup, so this guide builds a practical local image for the Vicidial web layer and starts it with MariaDB.

For a full production-oriented multi-service stack (Asterisk, reverse proxy TLS, backups, daemon processes), see `PRODUCTION_SETUP.md`.

## Build the image

```bash
docker build -t vicidial-web:local .
```

## Build and start the application stack

```bash
docker compose up --build -d
```

## Check status

```bash
docker compose ps
```

## Open app

Use `http://localhost:8080/agc/vicidial.php`.

Note: `http://localhost:8080` can return `403 Forbidden` because the legacy app does not expose a root index page.

## Stop stack

```bash
docker compose down
```
