#!/usr/bin/env bash
# Smoke test runner: boots the dev environment + Electron, runs the core-feature
# smoke suite, and tears everything down. Re-runnable after every change.
#
# Usage:
#   scripts/smoke/run.sh            # full cycle: build-check, boot, test, teardown
#   scripts/smoke/run.sh --no-boot  # assume electron-watch + Electron already up
#
# Exit code 0 = smoke suite passed.

set -u
cd "$(dirname "$0")/../.."   # repo root
ROOT="$(pwd)"
CDP_PORT="${CDP_PORT:-9333}"
ELECTRON_BIN="$ROOT/static/node_modules/electron/dist/Electron.app/Contents/MacOS/Electron"
NO_BOOT=0
[ "${1:-}" = "--no-boot" ] && NO_BOOT=1

export JAVA_HOME="$HOME/.local/share/mise/installs/java/21.0.2/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

log() { echo "[smoke] $*"; }

STARTED_WATCH=0
STARTED_ELECTRON=0

cleanup() {
  if [ "$STARTED_ELECTRON" = "1" ]; then
    log "stopping Electron"
    pkill -f "MacOS/Electron.*remote-debugging-port=$CDP_PORT" 2>/dev/null || pkill -f "MacOS/Electron" 2>/dev/null
  fi
  if [ "$STARTED_WATCH" = "1" ]; then
    log "stopping electron-watch"
    pkill -f "shadow-cljs" 2>/dev/null
  fi
}
trap cleanup EXIT

if [ "$NO_BOOT" = "0" ]; then
  # --- boot electron-watch (serves :3001 + builds static/electron.js) ---
  if ! curl -s -o /dev/null http://localhost:3001; then
    log "starting electron-watch (this takes ~40s)..."
    nohup yarn electron-watch > /tmp/logseq-smoke-watch.log 2>&1 &
    STARTED_WATCH=1
    # wait for the http server + electron.js bundle
    for i in $(seq 1 60); do
      if curl -s -o /dev/null http://localhost:3001 && [ -f "$ROOT/static/electron.js" ]; then
        break
      fi
      sleep 2
    done
  else
    log "electron-watch already serving :3001"
  fi

  # --- boot Electron with CDP ---
  if ! curl -s -o /dev/null "http://localhost:$CDP_PORT/json"; then
    log "launching Electron (CDP :$CDP_PORT)..."
    nohup "$ELECTRON_BIN" . --remote-debugging-port="$CDP_PORT" > /tmp/logseq-smoke-electron.log 2>&1 &
    STARTED_ELECTRON=1
    for i in $(seq 1 30); do
      curl -s -o /dev/null "http://localhost:$CDP_PORT/json" && break
      sleep 1
    done
  else
    log "Electron CDP already up on :$CDP_PORT"
  fi
fi

if ! curl -s -o /dev/null "http://localhost:$CDP_PORT/json"; then
  log "ERROR: Electron CDP not reachable on :$CDP_PORT"
  exit 1
fi

# --- run the suite ---
log "running core-feature smoke suite..."
node "$ROOT/scripts/smoke/core-features.mjs" --cdp "$CDP_PORT"
CODE=$?

if [ "$CODE" = "0" ]; then
  log "PASS"
else
  log "FAIL (exit $CODE)"
fi
exit "$CODE"
