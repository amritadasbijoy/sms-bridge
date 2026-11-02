#!/usr/bin/env bash
# Encrypts the app's release signing key (~/.smsbridge) with a passphrase you type, checks that it
# decrypts, and stores the encrypted copy on the VPS (/root/smsbridge-signing-key-backups, mode 600).
# The passphrase is never shown, stored or sent anywhere. Without it the backup cannot be opened.
#
#   tools/backup-signing-key.sh
#
# Restore on a new Mac:
#   scp vps:/root/smsbridge-signing-key-backups/<file> . &&
#   openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in <file> | tar -xz -C ~   # asks for the passphrase
set -euo pipefail

SRC="$HOME/.smsbridge"
[ -f "$SRC/release.jks" ] && [ -f "$SRC/keystore.properties" ] || { echo "No signing key found in $SRC" >&2; exit 1; }

echo "Choose a passphrase for the backup (at least 12 characters). Store it in your password manager:"
read -r -s -p "Passphrase: " P1; echo
read -r -s -p "Repeat:     " P2; echo
[ "$P1" = "$P2" ] || { echo "The two entries differ. Nothing was done." >&2; exit 1; }
[ "${#P1}" -ge 12 ] || { echo "Use at least 12 characters. Nothing was done." >&2; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"; unset P1 P2 SMSBRIDGE_BACKUP_PASS' EXIT
FILE="smsbridge-signing-key-$(date +%Y%m%d-%H%M%S).tar.gz.enc"
export SMSBRIDGE_BACKUP_PASS="$P1"

tar -C "$HOME" -czf "$WORK/key.tar.gz" .smsbridge/release.jks .smsbridge/keystore.properties
openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt -pass env:SMSBRIDGE_BACKUP_PASS -in "$WORK/key.tar.gz" -out "$WORK/$FILE"

# Prove the backup opens with this passphrase before relying on it.
openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -pass env:SMSBRIDGE_BACKUP_PASS -in "$WORK/$FILE" -out "$WORK/check.tar.gz"
cmp -s "$WORK/key.tar.gz" "$WORK/check.tar.gz" || { echo "Verification failed. Nothing was uploaded." >&2; exit 1; }

ssh -o BatchMode=yes vps 'install -d -m 700 /root/smsbridge-signing-key-backups'
scp -q -o BatchMode=yes "$WORK/$FILE" "vps:/root/smsbridge-signing-key-backups/$FILE"
ssh -o BatchMode=yes vps "chmod 600 /root/smsbridge-signing-key-backups/$FILE"
echo "Done: encrypted backup verified and stored on the VPS as /root/smsbridge-signing-key-backups/$FILE"
echo "Keep the passphrase safe: without it this backup cannot be opened."
