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

## Current deployment (2026-11-02)

Production runs on the owner's VPS (Ubuntu 24.04, Docker 29) next to other services, so it does
**not** use the bundled `proxy` container: the VPS already runs Caddy as a system service on 80/443.

| Item | Value |
|---|---|
| Code | `/opt/sms-bridge` (a `git archive` of `develop` plus `backend/build/install`) |
| Containers | `db`, `api` (127.0.0.1:8080), `web` (127.0.0.1:8081); `docker compose up -d db api web` |
| `.env` | generated on the server (random DB password), mode 600, `COOKIE_SECURE=true`, `TRUST_PROXY=true`, registration closed |
| Public entry | host Caddy site `sms.techistbd.com`: `/api/*` and `/health` → 8080, everything else → 8081 |
| Sign-up | Closed by the server; friends join with one-time invite codes from Settings (the proxy no longer blocks the path) |
| Backups | root cron 03:15 → `/var/backups/smsbridge` (encrypted, 14 kept), passphrase `/root/.smsbridge-backup-pass` |

Host Caddy site (in `/etc/caddy/Caddyfile`, backed up before each change):

```
sms.techistbd.com {
	encode zstd gzip
	header Strict-Transport-Security "max-age=31536000"
	@api path /api/* /health
	handle @api {
		reverse_proxy 127.0.0.1:8080
	}
	handle {
		reverse_proxy 127.0.0.1:8081
	}
}
```

The owner account was created on 2026-11-02 (by temporarily letting the owner's IP through this
rule, then removing the exception). The server itself now also refuses new accounts. To create the
first account on a fresh install instead, use a tunnel from the Mac:

```bash
ssh -L 8081:127.0.0.1:8081 vps     # keep open, then open http://localhost:8081 and choose "Create an account"
```

Update: on the Mac, `cd backend && ./gradlew installDist`, then package with
`COPYFILE_DISABLE=1 tar` (macOS otherwise adds `._*` files that break the web build), copy to the VPS,
unpack over `/opt/sms-bridge` (keeps `infrastructure/.env`), and run
`docker compose up -d --build api web` there.

### Tester APK downloads

`https://sms.techistbd.com/download/sms-bridge.apk` (latest) and `sms-bridge-<version>.apk`, served by
the host Caddy from `/opt/sms-bridge-downloads` (with a `.sha256` file). These are **debug-signed**
builds (`app.smsbridge.debug`) signed with the Mac's `~/.android/debug.keystore`: updates only install
over earlier ones if they are built with the same keystore, so keep a backup of that file.

Publish a new build: raise `versionCode`/`versionName` in `android/app/build.gradle.kts`, run
`./gradlew :app:assembleDebug`, copy the APK to `/opt/sms-bridge-downloads/sms-bridge-<version>.apk`
and over `sms-bridge.apk`, regenerate the `.sha256`, and update `latest.json`
(`{"versionCode":8,"versionName":"0.6.0","url":"/download/sms-bridge-0.6.0.apk","sha256":"…"}`): installed
apps read it (at most every 12 hours) and show "Update available".

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
