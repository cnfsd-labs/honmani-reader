#!/usr/bin/env bash
# Release gate for an APK:
#   1. Native libraries are compatible with 16 KB memory pages (zip alignment + ELF LOAD
#      segment alignment for 64-bit ABIs), as Google Play requires for targetSdk 35+.
#   2. No permissions that Google Play restricts or that Honmani must never need.
set -euo pipefail

apk="${1:?usage: check-apk.sh <path-to-apk>}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?ANDROID_HOME or ANDROID_SDK_ROOT must be set}}"
build_tools="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
failed=0

echo "Using build-tools $(basename "$build_tools")"

echo "== 16 KB zip alignment"
if ! "$build_tools/zipalign" -c -P 16 4 "$apk"; then
    echo "FAIL: uncompressed native libraries are not 16 KB aligned in the APK"
    failed=1
fi

echo "== 16 KB ELF segment alignment"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
unzip -q "$apk" 'lib/arm64-v8a/*.so' 'lib/x86_64/*.so' -d "$work" 2>/dev/null || true
shopt -s nullglob
libs=("$work"/lib/*/*.so)
if [ ${#libs[@]} -eq 0 ]; then
    echo "No 64-bit native libraries."
fi
for so in "${libs[@]}"; do
    # objdump prints alignment as 2**N; 16 KB is 2**14.
    min_align="$(objdump -p "$so" | awk '/LOAD/ { sub(/2\*\*/, "", $NF); print $NF }' | sort -n | head -1)"
    name="${so#"$work"/}"
    if [ -z "$min_align" ] || [ "$min_align" -lt 14 ]; then
        echo "FAIL: $name LOAD alignment 2**${min_align:-?} (< 2**14)"
        failed=1
    else
        echo "ok: $name (2**$min_align)"
    fi
done

echo "== Bundled reader"
# ttu needs its pages and its _app/ directory (JavaScript, CSS); asset filters have dropped the
# latter before.
entries="$(unzip -Z1 "$apk")"
for required in 'assets/ttu/index.html' 'assets/ttu/manage.html' 'assets/ttu/b.html'; do
    if ! grep -qxF "$required" <<<"$entries"; then
        echo "FAIL: $required is missing"
        failed=1
    fi
done
for pattern in '^assets/ttu/_app/immutable/.*\.js$' '^assets/ttu/_app/immutable/.*\.css$'; do
    if ! grep -qE "$pattern" <<<"$entries"; then
        echo "FAIL: no file matching $pattern"
        failed=1
    fi
done
for script in 'assets/honmani/text.js' 'assets/honmani/tap-bridge.js' 'assets/honmani/ttu-defaults.js' 'assets/honmani/keep-position.js' \
  'assets/honmani/library-bridge.js' 'assets/honmani/help-text.js' \
  'assets/honmani/book-end.js' \
  'assets/honmani/book-shown.js' 'assets/honmani/book-frame.js' 'assets/samples/tebukuro.epub'; do
    if ! grep -qxF "$script" <<<"$entries"; then
        echo "FAIL: $script is missing"
        failed=1
    fi
done
echo "ttu files: $(grep -c '^assets/ttu/' <<<"$entries")"
# The licences screen shows these; source maps (used to list the libraries) must not ship.
for notice in 'assets/licenses/gpl-3.0.txt' 'assets/licenses/apache-2.0.txt' 'assets/licenses/ttu.txt' \
  'assets/licenses/ttu-libraries.txt'; do
    if ! grep -qxF "$notice" <<<"$entries"; then
        echo "FAIL: $notice is missing"
        failed=1
    fi
done
if grep -qE '^assets/ttu/.*\.map$' <<<"$entries"; then
    echo "FAIL: source maps in the APK"
    failed=1
fi

echo "== Restricted permissions"
forbidden=(
    android.permission.MANAGE_EXTERNAL_STORAGE
    android.permission.READ_EXTERNAL_STORAGE
    android.permission.WRITE_EXTERNAL_STORAGE
    android.permission.QUERY_ALL_PACKAGES
    android.permission.REQUEST_INSTALL_PACKAGES
    android.permission.SYSTEM_ALERT_WINDOW
    android.permission.READ_MEDIA_IMAGES
    android.permission.READ_MEDIA_VIDEO
    android.permission.READ_MEDIA_AUDIO
    android.permission.BIND_ACCESSIBILITY_SERVICE
    android.permission.BIND_NOTIFICATION_LISTENER_SERVICE
)
permissions="$("$build_tools/aapt2" dump permissions "$apk")"
echo "$permissions"
for permission in "${forbidden[@]}"; do
    if grep -qF "'$permission'" <<<"$permissions"; then
        echo "FAIL: forbidden permission $permission"
        failed=1
    fi
done

if [ "$failed" -ne 0 ]; then
    echo "APK checks failed."
    exit 1
fi
echo "APK checks passed."
