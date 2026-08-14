# Vicidial PHP Control API

This service provides a modern PHP API layer in front of Vicidial AGC and non-agent APIs.

## Why this layer

- Keeps Vicidial internals unchanged.
- Gives mobile/web clients stable JSON endpoints.
- Adds API key protection and centralized audit-ready request handling.

## Endpoints

- `GET /health`
- `POST /api/agent-campaigns`
- `POST /api/agent-login`
- `POST /api/agent-active-lead`
- `POST /api/agent-action`
- `POST /api/live-sessions`
- `POST /api/ui-auth`
- `POST /api/login-check`
- `POST /api/external-dial`
- `POST /api/monitor`
- `POST /api/barge`

## Auth

Send header:

- `X-Api-Key: <APP_API_KEY>`

## Environment

Copy `.env.example` to `.env` and set values.

Set `VICIDIAL_AGENT_LOGIN_URL` to your classic agent login URL, usually `https://your-domain/agc/vicidial.php`. If it is blank, the API infers it from `VICIDIAL_AGC_API_URL`.

## Local run

With PHP built-in server:

```bash
cd php-control-api
php -S 0.0.0.0:8090 -t public
```

## Example requests

```bash
curl -s -X POST http://localhost:8090/api/login-check \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: change-me" \
  -d '{"agentUser":"1000"}'
```

```bash
curl -s -X POST http://localhost:8090/api/external-dial \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: change-me" \
  -d '{"agentUser":"1000","phoneNumber":"7275551212","phoneCode":"1"}'
```

```bash
curl -s -X POST http://localhost:8090/api/barge \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: change-me" \
  -d '{"phoneLogin":"350a","sessionId":"8600051","serverIp":"10.10.10.16"}'
```
