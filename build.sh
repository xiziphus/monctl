#!/bin/zsh
# Builds MonCtl.app into ~/Applications and installs the monctl CLI into ~/bin.
emulate -L zsh
set -eu

SRC=${0:A:h}
APP=~/Applications/MonCtl.app

echo "==> building MonCtl.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key>              <string>MonCtl</string>
  <key>CFBundleDisplayName</key>       <string>MonCtl</string>
  <key>CFBundleIdentifier</key>        <string>local.monctl</string>
  <key>CFBundleExecutable</key>        <string>MonCtl</string>
  <key>CFBundlePackageType</key>       <string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key>           <string>1</string>
  <key>LSMinimumSystemVersion</key>    <string>11.0</string>
  <key>LSUIElement</key>               <true/>
  <key>NSHighResolutionCapable</key>   <true/>
</dict>
</plist>
PLIST

swiftc -O -framework Cocoa -o "$APP/Contents/MacOS/MonCtl" "$SRC/MonCtl.swift"

# Locally built, so no quarantine flag — but strip it defensively and ad-hoc sign
# so macOS keeps the same identity across rebuilds instead of re-prompting.
xattr -cr "$APP" 2>/dev/null || true
codesign --force --deep --sign - "$APP" 2>/dev/null || echo "    (ad-hoc signing skipped)"

echo "==> building alsread (ambient light sensor)"
clang -O2 -o "$SRC/alsread" "$SRC/alsread.c" -framework CoreFoundation -framework IOKit
install -m 755 "$SRC/alsread" ~/bin/alsread

echo "==> building dimmer (software gamma fine-dim)"
clang -O2 -o "$SRC/dimmer" "$SRC/dimmer.c" -framework CoreGraphics -framework CoreFoundation
install -m 755 "$SRC/dimmer" ~/bin/dimmer

echo "==> building ddcprobe (raw VCP)"
clang -O2 -o "$SRC/ddcprobe" "$SRC/ddcprobe.c" -framework IOKit -framework CoreFoundation
install -m 755 "$SRC/ddcprobe" ~/bin/ddcprobe

echo "==> installing monctl CLI"
mkdir -p ~/bin
ln -sfn "$SRC/monctl" ~/bin/monctl

echo
echo "done."
echo "  CLI :  monctl status"
echo "  App :  open $APP     (add to Login Items to keep it around)"
