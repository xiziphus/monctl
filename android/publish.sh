#!/bin/bash
# Build the app and publish it to the update channel webui/server.py serves
# (~/.local/share/monctl/android). Phones running MonCtl pick it up the next
# time the app is opened.
#
# A release build (not debuggable), signed with ~/.config/monctl/release.keystore
# (settings in release.properties, both outside the repo). Back that key up:
# an APK signed with any other key cannot update the installed app.
set -euo pipefail
cd "$(dirname "$0")"
./gradlew -q assembleRelease
python3 - app/build/outputs/apk/release "$HOME/.local/share/monctl/android" <<'PY'
import hashlib, json, os, shutil, sys
out, chan = sys.argv[1], sys.argv[2]
meta = json.load(open(os.path.join(out, "output-metadata.json")))["elements"][0]
apk = os.path.join(out, meta["outputFile"])
data = open(apk, "rb").read()
os.makedirs(chan, exist_ok=True)
# APK first, then version.json, each via rename: a phone checking mid-publish
# sees either the old pair or the new APK under the old version (whose sha256
# then fails the check and is retried), never a half-written file.
tmp = os.path.join(chan, ".MonCtl.apk.tmp")
shutil.copyfile(apk, tmp); os.replace(tmp, os.path.join(chan, "MonCtl.apk"))
v = {"versionCode": meta["versionCode"], "versionName": meta["versionName"],
     "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
tmp = os.path.join(chan, ".version.json.tmp")
json.dump(v, open(tmp, "w")); os.replace(tmp, os.path.join(chan, "version.json"))
print(f"published {v['versionName']} ({v['versionCode']}), {len(data) // 1024} KB")
PY
