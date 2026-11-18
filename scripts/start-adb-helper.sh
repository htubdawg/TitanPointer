#!/bin/sh
# Starts the TitanPointer helper on a non-rooted Titan over ADB.
# Usage: scripts/start-adb-helper.sh <token> [port]
# The token and port are shown in the app under "ADB helper (no root)".
# Run again after every reboot of the phone.
set -e
TOKEN="$1"
PORT="${2:-47321}"
if [ -z "$TOKEN" ]; then
  echo "usage: $0 <token-from-the-app> [port]" >&2
  exit 1
fi
APK=$(adb shell pm path com.titanpointer | head -n1 | tr -d '\r' | sed 's/^package://')
if [ -z "$APK" ]; then
  echo "TitanPointer is not installed on the connected device." >&2
  exit 1
fi
LIB="$(dirname "$APK")/lib/arm64/libtitanpad.so"
adb shell "pkill -f 'titanpa[d] serve'; cp '$LIB' /data/local/tmp/titanpad && chmod 755 /data/local/tmp/titanpad && setsid nohup /data/local/tmp/titanpad serve $PORT $TOKEN >/dev/null 2>&1 < /dev/null &"
echo "Helper started on the phone (port $PORT). Open TitanPointer; it should connect within a few seconds."
