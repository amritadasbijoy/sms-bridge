# Deployment (self-hosted server)

Status: the stack is built and verified **locally** (Docker Desktop). It has **not** been deployed
to any VPS; Phase 10 does that, only with the owner's explicit go-ahead.

Target: Ubuntu 22.04/24.04 VPS (4 vCPU / 16 GB / 200 GB is plenty; the stack idles at a few
hundred MB), Docker Engine with the Compose plugin, and a DNS name pointing at the server.

## Layout

```
infrastructure/
├── docker-compose.yml     db (PostgreSQL 16, internal network only) + api (Ktor) + web (dashboard) + proxy (Caddy, profile "proxy")
├── .env.example           copy to .env (never commit .env)
├── proxy/Caddyfile        HTTPS with automatic certificates; /api/* and /health to api, the rest to web
└── scripts/backup-db.sh   encrypted pg_dump with retention
```

| Container | Exposure | Hardening |
|---|---|---|
| `db` | none (internal Docker network with no internet route) | data in volume `db-data`, health check |
| `api` | `127.0.0.1:8080` only; public traffic goes through Caddy | non-root user, read-only root FS, `no-new-privileges`, health check |
| `web` | `127.0.0.1:8081` only | static files only, read-only root FS, `no-new-privileges`, strict CSP (`script-src 'self'`) |
| `proxy` | `:80`, `:443` | HSTS; API paths to `api`, everything else to `web` |

All services: `restart: unless-stopped`, JSON log rotation (10 MB × 5).

## First deployment (Phase 10 runbook draft)

```bash
# On the VPS, as a sudo user (not root):
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 ufw
sudo ufw default deny incoming && sudo ufw default allow outgoing
sudo ufw allow OpenSSH && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp
sudo ufw enable                         # PostgreSQL (5432) and 8080 stay closed

git clone <your private repo> sms-bridge && cd sms-bridge/backend && ./gradlew installDist
cd ../infrastructure && cp .env.example .env && chmod 600 .env
#   POSTGRES_PASSWORD=$(openssl rand -base64 32)   SMSBRIDGE_COOKIE_SECURE=true
#   SMSBRIDGE_TRUST_PROXY=true                       SMSBRIDGE_DOMAIN=sms.example.org
docker compose --profile proxy up -d --build
curl -fsS https://sms.example.org/health
```

Then create the account at once (the first registration wins; after that registration is closed).

Note: Docker publishes ports by editing iptables directly, which bypasses `ufw`. That is why the
API binds to `127.0.0.1` and the database publishes nothing; only Caddy's 80/443 are public.

## Backups

```bash
openssl rand -base64 32 | sudo tee /root/.smsbridge-backup-pass >/dev/null && sudo chmod 600 /root/.smsbridge-backup-pass
# daily at 03:15 (root crontab)
15 3 * * * BACKUP_PASSPHRASE_FILE=/root/.smsbridge-backup-pass /home/<user>/sms-bridge/infrastructure/scripts/backup-db.sh /var/backups/smsbridge
```

The script writes `smsbridge-<UTC timestamp>.sql.gz.enc` (`pg_dump | gzip | openssl aes-256-cbc -pbkdf2 -iter 600000`),
mode 600, keeps the newest 14. Copy them off the server (another host or offline drive). Keep the
passphrase file somewhere other than next to the backups.

Message content in the database is already end-to-end encrypted by the phones (from Phase 6);
the backup encryption protects account data, metadata and audit logs.

Verified locally (2026-11-01): the backup ran, the file contained no plaintext SQL, it decrypted to all 12 tables,
and a wrong passphrase was rejected.

## Restore

```bash
cd sms-bridge/infrastructure
docker compose stop api
openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -pass file:/root/.smsbridge-backup-pass \
    -in /var/backups/smsbridge/smsbridge-YYYYmmdd-HHMMSS.sql.gz.enc | gunzip > /tmp/restore.sql
# Into an EMPTY database (destructive for the current data: confirm first, keep the old volume until verified):
docker compose exec -T db dropdb -U smsbridge smsbridge
docker compose exec -T db createdb -U smsbridge smsbridge
docker compose exec -T db psql -U smsbridge -d smsbridge -v ON_ERROR_STOP=1 < /tmp/restore.sql
shred -u /tmp/restore.sql
docker compose start api && curl -fsS http://127.0.0.1:8080/health
```

## Updates

`git pull && (cd ../backend && ./gradlew installDist) && docker compose up -d --build api`.
Flyway applies new migrations on start; migrations are additive and tested (no destructive changes).
