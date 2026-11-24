#!/usr/bin/env bash
# Builds, signs and publishes an app update; every installed phone is told at once.
#
#   tools/publish-release.sh vps   "What's new in this version" [--required]
#   tools/publish-release.sh local "Test release"               [--required]
#
# "vps" also pushes the current commit to GitHub (main, fast-forward only) and creates the GitHub
# release "v<versionName>" with the same signed APK. Set SKIP_GITHUB=1 to leave GitHub out, or
# GITHUB_DRY_RUN=1 to print what would be done.
#
# Raise versionCode/versionName in android/app/build.gradle.kts first. The release key comes from
# ~/.smsbridge/keystore.properties (never in the repository). The admin token is read on the
# server from infrastructure/.env and never printed.
set -euo pipefail

TARGET="${1:?usage: publish-release.sh vps|local \"release notes\" [--required]}"
NOTES="${2:-}"
REQUIRED=false
[ "${3:-}" = "--required" ] && REQUIRED=true
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE="$ROOT/android/app/build.gradle.kts"
CODE=$(grep -E '^\s*versionCode = [0-9]+' "$GRADLE" | grep -oE '[0-9]+')
NAME=$(grep -E '^\s*versionName = "' "$GRADLE" | sed -E 's/.*"(.*)".*/\1/')
EXPECTED_CERT="40ba726992e971e0cf53eb776f1e315cef349fb4e105d31aab7ec95defd96195"
APKSIGNER=$(ls -d "$HOME"/Library/Android/sdk/build-tools/*/apksigner | tail -1)

# "local" builds the test-only releaseTest variant (same key, may reach the local HTTP stack).
VARIANT=release; TASK=assembleRelease
if [ "$TARGET" = "local" ]; then VARIANT=releaseTest; TASK=assembleReleaseTest; fi
echo "Building SMS Bridge $NAME ($CODE, $VARIANT)…"
(cd "$ROOT/android" && ./gradlew -q ":app:$TASK")
APK="$ROOT/android/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
CERT=$("$APKSIGNER" verify --print-certs "$APK" | grep -m1 'SHA-256 digest' | awk '{print $NF}')
if [ "$CERT" != "$EXPECTED_CERT" ]; then
  echo "Refusing to publish: the APK is not signed with the SMS Bridge release key." >&2
  exit 1
fi
SHA=$(shasum -a 256 "$APK" | cut -c1-64)
FILE="sms-bridge-$NAME.apk"
mkdir -p "$ROOT/dist"
cp "$APK" "$ROOT/dist/$FILE"
BODY=$(python3 -c 'import json,sys; print(json.dumps({"versionCode":int(sys.argv[1]),"versionName":sys.argv[2],"url":"/download/"+sys.argv[3],"sha256":sys.argv[4],"required":sys.argv[5]=="true","notes":sys.argv[6]}))' "$CODE" "$NAME" "$FILE" "$SHA" "$REQUIRED" "$NOTES")
LEGACY=$(python3 -c 'import json,sys; print(json.dumps({"versionCode":int(sys.argv[1]),"versionName":sys.argv[2],"url":"/download/"+sys.argv[3],"sha256":sys.argv[4]}))' "$CODE" "$NAME" "$FILE" "$SHA")

case "$TARGET" in
  vps)
    D=/opt/sms-bridge-downloads
    scp -q -o BatchMode=yes "$APK" "vps:/root/$FILE"
    ssh -o BatchMode=yes vps "set -e; install -m 644 /root/$FILE $D/$FILE; rm /root/$FILE; cp $D/$FILE $D/sms-bridge.apk; cd $D && sha256sum $FILE > $FILE.sha256"
    printf '%s\n' "$LEGACY" | ssh -o BatchMode=yes vps "cat > $D/latest.json.tmp && chmod 644 $D/latest.json.tmp && mv $D/latest.json.tmp $D/latest.json"
    printf '%s' "$BODY" | ssh -o BatchMode=yes vps 'T=$(grep "^SMSBRIDGE_ADMIN_TOKEN=" /opt/sms-bridge/infrastructure/.env | cut -d= -f2-); curl -sS -o /dev/null -w "announce: HTTP %{http_code}\n" -X POST -H "Authorization: Bearer $T" -H "Content-Type: application/json" --data-binary @- http://127.0.0.1:8080/api/v1/admin/releases'
    ;;
  local)
    D="$ROOT/infrastructure/downloads"
    mkdir -p "$D"
    cp "$APK" "$D/$FILE"; cp "$APK" "$D/sms-bridge.apk"; printf '%s\n' "$LEGACY" > "$D/latest.json"
    T=$(grep '^SMSBRIDGE_ADMIN_TOKEN=' "$ROOT/infrastructure/.env" | cut -d= -f2-)
    printf '%s' "$BODY" | curl -sS -o /dev/null -w "announce: HTTP %{http_code}\n" -X POST -H "Authorization: Bearer $T" -H "Content-Type: application/json" --data-binary @- http://127.0.0.1:8080/api/v1/admin/releases
    ;;
  *) echo "target must be vps or local" >&2; exit 1 ;;
esac
echo "Published SMS Bridge $NAME ($CODE), sha256 $SHA"

# GitHub Releases: the same APK for people who download it there. Failures here never undo the
# release above; they are reported so the step can be repeated.
github_release() {
  local repo="amritadasbijoy/sms-bridge" tag="v$NAME" run=""
  [ "${GITHUB_DRY_RUN:-}" = "1" ] && run="echo [dry run]"
  if [ -n "$(git -C "$ROOT" status --porcelain --untracked-files=no)" ]; then
    echo "GitHub: skipped, uncommitted changes (commit, then run the gh release step by hand)." >&2; return 1
  fi
  if gh release view "$tag" -R "$repo" >/dev/null 2>&1; then
    echo "GitHub: release $tag already exists, skipped."; return 0
  fi
  local commit notes
  commit=$(git -C "$ROOT" rev-parse HEAD)
  notes=$(mktemp)
  cat > "$notes" <<NOTES
${NOTES:-SMS Bridge $NAME}

## Install
1. Download **$FILE** below on your Android phone (Android 8.0 or newer).
2. Open it and tap **Install**. If Android asks, allow installing from your browser.
3. On your computer, open **https://sms.techistbd.com**, then in the app tap **Link a computer** and scan the QR code or type the 8-digit code.

Already installed? The app offers this update by itself (**Settings → App updates**).

## Check the download
SHA-256: \`$SHA\`
NOTES
  $run git -C "$ROOT" push -q origin "$commit:refs/heads/main" || { echo "GitHub: push to main failed (not a fast-forward?)." >&2; rm -f "$notes"; return 1; }
  $run gh release create "$tag" "$ROOT/dist/$FILE" -R "$repo" --target "$commit" --title "SMS Bridge $NAME" --notes-file "$notes" --latest \
    || { echo "GitHub: creating the release failed." >&2; rm -f "$notes"; return 1; }
  rm -f "$notes"
  echo "GitHub: https://github.com/$repo/releases/tag/$tag"
}
if [ "$TARGET" = "vps" ] && [ "${SKIP_GITHUB:-}" != "1" ]; then github_release || true; fi
