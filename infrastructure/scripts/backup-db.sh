#!/usr/bin/env bash
# Encrypted PostgreSQL backup for the SMS Bridge stack.
#
#   BACKUP_PASSPHRASE_FILE=/root/.smsbridge-backup-pass ./backup-db.sh /var/backups/smsbridge
#
# Writes smsbridge-YYYYmmdd-HHMMSS.sql.gz.enc (pg_dump | gzip | openssl AES-256 with PBKDF2) and
# keeps the newest 14. The dump holds account data and *encrypted* message events only.
# Restore: see docs/deployment.md.
set -euo pipefail

DEST="${1:?usage: backup-db.sh <destination-dir>}"
PASSFILE="${BACKUP_PASSPHRASE_FILE:?set BACKUP_PASSPHRASE_FILE to a file containing the backup passphrase}"
KEEP="${KEEP:-14}"
COMPOSE_DIR="$(cd "$(dirname "$0")/.." && pwd)"

[ -r "$PASSFILE" ] || { echo "cannot read $PASSFILE" >&2; exit 1; }
mkdir -p "$DEST"
chmod 700 "$DEST"

OUT="$DEST/smsbridge-$(date -u +%Y%m%d-%H%M%S).sql.gz.enc"
docker compose --project-directory "$COMPOSE_DIR" exec -T db pg_dump -U smsbridge -d smsbridge --no-owner \
  | gzip -9 \
  | openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt -pass "file:$PASSFILE" -out "$OUT.partial"
mv "$OUT.partial" "$OUT"
chmod 600 "$OUT"
echo "wrote $OUT ($(du -h "$OUT" | cut -f1))"

# Retention: delete only our own older backup files, newest $KEEP kept.
ls -1t "$DEST"/smsbridge-*.sql.gz.enc 2>/dev/null | tail -n +"$((KEEP + 1))" | while read -r old; do
  rm -f -- "$old"
done
