#!/bin/bash
# publish-ota.sh <signed.apk> [notes-file] : publish an APK to the hub's /app/latest + /app/apk.
set -eu
APK=${1:?usage: publish-ota.sh <signed.apk> [notes-file]}
NOTES=${2:-}
APP_DIR=${APP_DIR:-/opt/hermes-hub/app}
AAPT2=${AAPT2:-/opt/android-sdk/build-tools/35.0.0/aapt2}
B=$("$AAPT2" dump badging "$APK" | grep -m1 '^package:')
CODE=$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$B")
NAME=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$B")
[ -n "$CODE" ] && [ -n "$NAME" ] || { echo "cannot read version from $APK" >&2; exit 1; }
FILE=HermesCompanion-$NAME.apk
mkdir -p "$APP_DIR"
cp "$APK" "$APP_DIR/$FILE.tmp" && mv "$APP_DIR/$FILE.tmp" "$APP_DIR/$FILE"
ls -t "$APP_DIR"/*.apk | tail -n +3 | xargs -r rm -f --
python3 - "$APP_DIR" "$FILE" "$CODE" "$NAME" "$NOTES" <<'PY'
import hashlib, json, os, sys
d, f, code, name, notes = sys.argv[1:]
data = open(os.path.join(d, f), "rb").read()
n = open(notes).read().strip() if notes else ""
out = {"versionCode": int(code), "versionName": name, "notes": n, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data), "file": f}
t = os.path.join(d, "latest.json.tmp")
json.dump(out, open(t, "w"))
os.replace(t, os.path.join(d, "latest.json"))
print(json.dumps(out))
PY
