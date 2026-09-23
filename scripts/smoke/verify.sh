#!/usr/bin/env bash
# Full local verification for a change: lint + unit tests + core-feature smoke.
# Run this after every runtime-affecting change before committing/merging.
#
#   scripts/smoke/verify.sh              # lint+unit, then boot Electron + smoke
#   scripts/smoke/verify.sh --skip-smoke # only lint + unit tests
#   scripts/smoke/verify.sh --no-boot    # smoke assumes env already running
#
# Exit 0 = everything passed.

set -u
cd "$(dirname "$0")/../.."
ROOT="$(pwd)"
export JAVA_HOME="$HOME/.local/share/mise/installs/java/21.0.2/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

SKIP_SMOKE=0
BOOT_ARG=""
for a in "$@"; do
  case "$a" in
    --skip-smoke) SKIP_SMOKE=1 ;;
    --no-boot) BOOT_ARG="--no-boot" ;;
  esac
done

log() { echo "[verify] $*"; }

log "step 1/2: lint + unit tests (bb dev:lint-and-test)"
bb dev:lint-and-test > /tmp/logseq-verify-lint.log 2>&1
LINT=$?
tail -4 /tmp/logseq-verify-lint.log | sed 's/^/    /'
if [ "$LINT" != "0" ]; then
  log "FAIL: lint/unit tests (see /tmp/logseq-verify-lint.log)"
  exit 1
fi
log "lint + unit tests: PASS"

if [ "$SKIP_SMOKE" = "1" ]; then
  log "step 2/2: smoke skipped (--skip-smoke)"
  exit 0
fi

log "step 2/2: core-feature smoke (Electron)"
scripts/smoke/run.sh $BOOT_ARG
SMOKE=$?
if [ "$SMOKE" != "0" ]; then
  log "FAIL: smoke suite"
  exit 1
fi

log "ALL CHECKS PASSED"
exit 0
