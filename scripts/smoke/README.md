# Smoke tests

Manual/end-to-end smoke tests for the minimal file-graph-only build. These run
against a real Electron instance and verify the core features that must keep
working after every change.

## What `core-features.mjs` checks

| # | Feature | What it does |
|---|---------|--------------|
| 1 | journals | today's journal loads and a block can be appended |
| 2 | bidirectional link | `[[ref]]` renders, click navigates, backlink shows under "Linked references" |
| 3 | query | `{{query [[X]]}}` renders a live result list |
| 4 | task | `TODO` block renders and cycles to `DOING`/`DONE` on click |
| 5 | graph view | canvas + control panel render |
| 6 | hygiene | no fatal console errors during the run |

Every check uses a unique run tag (`Smoke<timestamp>`) for its pages/blocks, so
the suite is **idempotent** — re-running never collides with prior data, and the
journal is not polluted with stale assertions.

## How to run

The suite needs the dev build serving and an Electron instance with CDP open:

```bash
# 1. serve the app + build the electron bundle (once, keep running)
yarn electron-watch

# 2. launch Electron with a debug port and open a file graph
./static/node_modules/electron/dist/Electron.app/Contents/MacOS/Electron . \
    --remote-debugging-port=9333

# 3. run the suite
yarn smoke                 # uses CDP_PORT or 9333
# or
node scripts/smoke/core-features.mjs --cdp 9333
```

Or let the wrapper boot everything (electron-watch + Electron) and tear it down:

```bash
yarn smoke:run             # full cycle
scripts/smoke/run.sh       # same
scripts/smoke/run.sh --no-boot   # env already up; just run the suite (leaves it running)
```

### Full verification (lint + unit + smoke)

`verify.sh` chains the standard lint/unit gate with the smoke suite — use it
as the single pre-merge check after any runtime-affecting change:

```bash
yarn verify                 # bb dev:lint-and-test, then boot Electron + smoke
scripts/smoke/verify.sh --no-boot     # lint+unit, smoke against already-running env
scripts/smoke/verify.sh --skip-smoke  # lint+unit only
```

Exit code is `0` when all checks pass, `1` otherwise — safe to wire into a
pre-merge checklist.

## When to run

Run it after any change that could affect the file-graph runtime — especially
the ongoing DB-removal work (Cut 3b/3c), which touches shared handlers,
components, and the shortcut config. It complements `bb dev:lint-and-test`
(unit + lint) with real-app coverage of the features users actually rely on.
