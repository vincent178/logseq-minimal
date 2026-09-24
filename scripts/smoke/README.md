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
| 4 | task | a task block renders its marker and cycles state on click. Workflow-aware: it reads the graph's `:preferred-workflow` and uses `LATER`→`NOW`/`DONE` for `:now` graphs, `TODO`→`DOING`/`DONE` for `:todo` graphs. A `TODO` block in a `:now` graph is plain text (no marker) by design, so the marker word must match the configured workflow. |
| 5 | image render | writes a real PNG into `assets/`, inserts `![...](../assets/...)`, asserts the rendered `<img>` loads (`naturalWidth`/`naturalHeight` > 0) — regression for the nil-`src` `asset-container` crash |
| 6 | graph view | canvas + control panel render |
| 7 | hygiene | no fatal console errors during the run |

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

## Reliability

The suite is designed to be a **trustworthy gate**: if it fails, the failure
should mean a real regression, not an environment flake. Key properties:

- **Deterministic navigation** — `gotoJournal()` resolves the current page via
  the API and compares `journalDay`, then condition-waits for the router to
  land. It never assumes a nav succeeded, and fails fast with a clear error if
  it can't reach today's journal.
- **View-independent appends** — blocks are always appended to today's journal
  by explicit page name, so a check never depends on which page the app
  happened to be showing.
- **Self-cleaning** — every run deletes the pages and journal blocks it
  created (looked up via the block-tree API, not the DOM, because virtualized
  rendering keeps most blocks out of the DOM). Re-running never accumulates
  `Smoke*` data, and the journal stays small enough to render new blocks.
- **Async deletes are awaited** — `delete_page`/`remove_block` flush to disk
  asynchronously; the suite condition-waits for pages to actually disappear
  before exiting, so no ghost pages break the next run.

### If the smoke suite fails

1. Read the per-check detail (`ref=true nav=false ...`) — it says which step
   broke.
2. `❌ gotoJournal failed: current page is "..."` means the app couldn't reach
   today's journal (app not ready, or a graph/page-state problem), not a flaky
   timeout.
3. Check for leftover `Smoke*` pages in the graph — they indicate an
   interrupted earlier run; delete them and re-run.
4. A failure that reproduces on a second run is a real regression —
   investigate the change, not the suite.
