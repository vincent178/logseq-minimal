# Benchmark: large page performance

Reproducible benchmark for how the app renders large pages in the browser dev
build. Companion to the harness in `scripts/perf/large-page.mjs`.

## TL;DR — what we found

Profiling a synthetic 1,000-block page showed the app **already handles raw
block volume well**: react-virtuoso virtualizes any page with ≥10 top-level
blocks, so a 1,000-block page mounts only ~38 DOM nodes, opens in ~250 ms, and
types with no per-keystroke re-render storm (char insert ~2 ms, Enter ~180 ms).

The dominant *user-visible* failure on "large pages" is **not raw render cost** —
it is a **blank render of a large referenced page**: a page that accumulates
many backlinks but has **no explicit page entity** (it is only ever *referenced*
via `[[...]]`, never *created*) renders nothing at all — no blocks, no title, no
"Page not found", no error. This reproduces independent of backlink count (50
refs hang just like 1,000), so it is a functional correctness bug on the
load-status await path in `page-aux`, surfaced most painfully on large/heavily
linked pages.

## Scenarios

| Scenario | Fixture | What it isolates |
|---|---|---|
| **largePage** | `create_page` + 1,000 blocks via `insert_batch_block` | The healthy render path for raw block volume (virtualized). |
| **referencedPage** | 50 blocks on a source page, each `[[Target]]`; target **never explicitly created** | The blank-render failure on a referenced-only page. |

## How to run

Prerequisite: the dev app serving on `:3001`:

```bash
yarn electron-watch     # or: yarn watch   (serves :3001, builds static/js)
```

Then:

```bash
node scripts/perf/large-page.mjs --runs 3 --blocks 1000 --refs 50 \
    --out docs/dev/benchmark-large-page.baseline.json
```

Flags: `--runs` (default 3), `--blocks` (default 1000), `--refs` (default 50),
`--timeout` (blank-detection timeout, default 15000 ms), `--url`, `--out`.

## Methodology

- **Browser**: Playwright launches the system Chrome (`channel: 'chrome'`)
  headless with a fresh incognito context per run (cold caches), fixed
  1440×900 viewport, DPR 1 — deterministic, no user profile.
- **Graph**: the app boots standalone on `:3001` with the in-memory **Demo**
  graph (the same path the e2e suite uses), so no native directory picker is
  needed. File-based + memory-fs backend.
- **Seeding**: pages are built with `window.logseq.api.create_page` /
  `insert_batch_block`, **not** through the editor, so seeding cost is isolated
  from render cost.
- **Navigation**: same-document `location.hash = '#/page/<name>'` (the app's
  own router). "Rendered" means **route-owned** content is present — the page's
  own `.ls-block` nodes or an explicit "Page not found" — never stale DOM left
  over from the previous route. This avoids false-positive open times.
- **Metrics**:
  - `openMs` — nav → route-owned render (or timeout ⇒ `blank: true`).
  - `keystrokeMs` — Enter → a *different* textarea becomes focused (p50 of 5).
  - `charInsertMs` — keystroke → char reflected in the textarea value (p50 of 5).
  - `longTasks` — >50 ms main-thread tasks (`PerformanceObserver('longtask')`).
  - `domBlocks` — `.ls-block` count after settle (small ⇒ virtualization working).

## Baseline (committed as `benchmark-large-page.baseline.json`)

Machine: GG-12076, Chrome headless, 2026-09-25. Median of 3 runs.

| Metric | largePage (1000 blocks) | referencedPage (50 refs, no entity) |
|---|---|---|
| openMs (median) | **256 ms** | **15222 ms → timeout (blank)** |
| blankRuns | 0 / 3 | **3 / 3** |
| keystrokeMs | 179 ms | — (nothing to edit) |
| charInsertMs | 2 ms | — |
| domBlocks | 38 (virtualized) | **0 (blank)** |
| longTasks | 3 | — |

**Primary symptom metric = `referencedPage.blankRuns` / `referencedPage.openMs`.**
The optimization target is to make the referenced page render (blankRuns → 0)
with openMs in the same ballpark as a normal page.

## Root cause (see task-2 report)

`frontend/components/page.cljs` `page-aux` `:init` does:

```clojure
(p/let [page-block (db-async/<get-block repo page-id-uuid-or-name)
        page-id (:db/id page-block)
        refs-count (db-async/<get-block-refs-count repo page-id)]
  (reset! *loading? false) ...)
```

For a referenced-only page, `db/get-page` returns nil on the frontend conn and
`<get-block` resolves to nil (the worker's `get-block-and-children` returns nil
for a block that isn't materialized on the frontend). `page` stays nil, so the
render gate `(when (and page (not loading?)) ...)` never mounts `page-inner`.
No promise rejection is surfaced, so the failure is a silent blank page rather
than a crash. (CPU profiles show the main thread ~99% idle during the hang — it
is a pending-promise stall / empty render, not a compute loop.)

Full analysis: `docs/dev/root-cause-large-page.md`.

## Fix & post-fix results (committed as `benchmark-large-page.after.json`)

Fix (`page-aux` `:init`, page.cljs): when `<get-block` returns nil for a
name-route page, check the worker for incoming references via the new
`<page-refs-count` endpoint. If the page has references (it is a "ghost" page
that exists only as a `[[...]]` target), create it via `page-handler/<create!`
with `{:redirect? false :reference? true}`, then re-fetch — so the
referenced-only page is materialized and renders like a normal page. If the
page has no references (a typo or nonexistent name), leave it nil so
`page-inner` shows "Page not found" instead of persisting an empty page.

| Metric | largePage (1000 blocks) | referencedPage (50 refs) |
|---|---|---|
| openMs (median) | 256 ms (was 256) | **255 ms (was 15222 timeout)** |
| blankRuns | 0 / 3 | **0 / 3 (was 3 / 3)** |
| keystrokeMs | 115 ms (was 179) | — |
| charInsertMs | 1 ms (was 2) | — |
| domBlocks | 38 (virtualized) | 1 (page shell) |

**Primary metric `referencedPage.blankRuns`: 3/3 → 0/3**, and `openMs` ~98%
faster (15222 → 255 ms). Secondary `largePage` metrics flat (no regression).
`bb dev:lint-and-test`: 171 tests, 0 failures.

### Typo / nonexistent page behavior

Pages that have no incoming references (e.g. a typo in the URL) now render
"Page not found" instead of being silently persisted as empty pages. The
benchmark harness detects this as a valid rendered state (`notFound: true`),
so `blankRuns` remains 0/3.

### Known limitation (pre-existing, not caused by the fix)

The "Linked references" panel is empty/collapsed even after the fix, because
`get_page_linked_references` returns `[]` for pages whose backlinks were added
via the api's `insert_batch_block` — this happens for pre-created pages too,
with the fix reverted, so it is a separate data-layer/indexing characteristic
of this fork, out of scope here. The fix restores page *rendering*.
