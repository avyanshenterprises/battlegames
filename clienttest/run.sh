#!/bin/sh
# Compiles the pure-Java client logic with plain javac and tests it (incl. against a live local server).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
B="$HERE/../app/src/main/java/com/avyansh/battleroyale"
OUT=$(mktemp -d)
DATA=$(mktemp -d)
javac -nowarn -d "$OUT" "$B/Json.java" "$B/GameClient.java" "$B/SnapshotInterpolator.java" "$B/InputReconciler.java" "$B/AimAssist.java" "$B/CameraRig.java" "$B/PositionalAudio.java" "$B/GunfireIndicators.java" "$B/MinimapMath.java" "$B/SquadModel.java" "$B/Weapon.java" "$HERE/ClientTests.java"
(cd "$HERE/../server" && QUIET=1 PORT=17778 TCP_PORT=17777 BR_FILL_SECONDS=0.5 PLANE_SPEED=5 DATA_DIR="$DATA" node src/server.js) &
SERVER_PID=$!
cleanup() {
  kill "$SERVER_PID" 2>/dev/null || true
  wait "$SERVER_PID" 2>/dev/null || true
  rm -rf "$OUT" "$DATA"
}
trap cleanup EXIT INT TERM
sleep 1
java -cp "$OUT" ClientTests 17777
