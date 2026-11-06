#!/bin/bash
# Cut a GitHub release: builds the signed release APK, tags the current commit,
# and attaches the APK with a changelog taken from the commits since the last
# release. Needs a clean, pushed tree and an authenticated `gh`.
#
#   android/release.sh            build, tag, publish
#   android/release.sh --dry-run  build and print the notes; change nothing
set -euo pipefail
cd "$(dirname "$0")"
DRY=0; [[ ${1-} == --dry-run ]] && DRY=1

if [[ $DRY == 0 ]]; then
  [[ -z $(git status --porcelain) ]] || { echo "release: commit or stash first" >&2; exit 1; }
  git fetch -q origin
  [[ $(git rev-parse HEAD) == $(git rev-parse origin/main) ]] || { echo "release: push main first" >&2; exit 1; }
fi

./gradlew -q assembleRelease
OUT=app/build/outputs/apk/release
read -r VER < <(python3 -c "import json;print(json.load(open('$OUT/output-metadata.json'))['elements'][0]['versionName'])")
APK="$OUT/MonCtl-$VER.apk"
cp "$OUT/app-release.apk" "$APK"
SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)

PREV=$(git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true)
RANGE=${PREV:+$PREV..}HEAD
NOTES=$(mktemp)
{
  echo "## Changes"
  git log --no-merges --format='- %s' "$RANGE" | grep -v '^- Co-Authored' || true
  echo
  echo "## APK"
  echo "SHA-256: \`$SHA\`"
  echo
  echo "Signed with the MonCtl release key. Android and Play Protect will say the app is from an unknown developer, because it is not distributed through Google Play. Choose **More details > Install anyway**. See the README."
} > "$NOTES"

if [[ $DRY == 1 ]]; then cat "$NOTES"; echo; echo "(dry run: $APK built, nothing tagged)"; exit 0; fi

gh release create "v$VER" "$APK" --title "MonCtl $VER" --notes-file "$NOTES" --target "$(git rev-parse HEAD)"
echo "released v$VER"
