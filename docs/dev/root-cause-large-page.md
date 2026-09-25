# Root cause: large referenced page renders blank

Reproduces against the baseline in `benchmark-large-page.md` /
`benchmark-large-page.baseline.json` (`referencedPage`: 3/3 runs blank, 0 DOM
blocks, 15 s timeout; main thread ~99% idle — a pending-promise stall, not a
compute loop).

## Symptom

Navigating to a page that has **only ever been referenced** (`[[Ghost]]`) and
**never explicitly created** renders a permanently blank content area — no
blocks, no page title, no "Page not found", no console error, no timeout. The
sidebar/chrome still renders, so the app looks alive but the page never mounts.

This surfaces most painfully on *large* pages (a heavily-linked note, a busy
journal, a namespace parent), because those are exactly the pages that get
referenced before they are created. So the user-perceived "large page is
slow/broken" is this correctness bug, not raw render throughput — raw block
volume is already handled well by virtualization (see baseline).

## Where it breaks

`src/main/frontend/components/page.cljs`:

- `page-aux` `:init` (line ~565) loads the page block before rendering:

  ```clojure
  (p/let [page-block (db-async/<get-block repo page-id-uuid-or-name)   ; L566
          page-id   (:db/id page-block)
          refs-count (when-not (or (ldb/class? page-block)
                                   (ldb/property? page-block))
                       (db-async/<get-block-refs-count repo page-id))]
    (reset! *loading? false) ...)                                      ; L570
  ```

- Render gate (line ~592):

  ```clojure
  (when (and page (not loading?))
    (page-inner ...))
  ```

- `frontend/db/async.cljs` `<get-block` (L80) first looks the page up on the
  **frontend** datascript conn (`db/get-page` → `ldb/get-page`), then asks the
  worker (`:thread-api/get-blocks`) to materialize it and transacts the result
  back. It has a `p/catch` that rethrows a `"get-block error"` ex-info.

## Why it hangs (mechanism)

A referenced-only page exists **only in the db-worker** (as the target of
`:block/refs` from other blocks). It is **not yet materialized on the frontend
conn** — there is no committed page entity with `:block.temp/load-status`.

Verified empirically against the running app (system Chrome, memory-fs Demo
graph):

| call | referenced-only page (`GhostZ`) | created page (`RealPage`) |
|---|---|---|
| `api.get_page` | `null` (1 ms) | entity (3 ms) |
| `api.get_page_blocks_tree` | `null` (1 ms) | blocks |
| nav `#/page/GhostZ` | **blank > 30 s, 0 blocks** | renders ~2 s |

For a referenced-only page, `<get-block` returns `nil` (the worker's
`get-block-and-children` returns `nil` when the block isn't found on the
frontend conn). With `page-block` nil, `page-id` is nil, the `refs-count` call
is skipped (`ldb/class?`/`ldb/property?` of nil), and `*loading?` does get
reset to false. **But** `*page` is reset to `(db/entity nil)` = nil, and
`page-inner` requires a page with a `:block/title` (or block) to render —
`(when (and page (not loading?)) (page-inner ...))` then `page-inner`'s own
`(if page (when (or title block?) ...) "Page not found")`. The entity stays
nil because nothing ever materializes the referenced-only page onto the
frontend conn, so the body renders nothing. The result is the silent blank
page (not even the "Page not found" fallback, because `page-aux`'s outer
`(when (and page ...))` gate fails first).

Contrast with a page that *was* created: `get_page` returns a real entity with
a `:db/id`, `<get-block` materializes it, `loading?` clears, `page-inner`
mounts.

## Why this is the "large page" bottleneck

- Plain 1,000-block page: open **256 ms**, 38 DOM nodes (virtualized), typing
  char 2 ms, Enter 179 ms — already fast. No long tasks.
- Referenced-only page: **never renders** regardless of ref count (50 refs hang
  just like 1,000). This is the dominant defect a user hits on a large,
  well-linked graph.

So the primary metric to fix is `referencedPage.blankRuns` → 0 (and
`referencedPage.openMs` down to a normal-page ballpark), which is a >99%
"improvement" on the failing scenario — far beyond the 30% bar.

## Fix (implemented in task-3, refined in PR review)

In `page-aux` `:init` (page.cljs ~L566), when `<get-block` returns nil and the
route is a name-route (not a uuid route), the code now checks whether the page
has any incoming references before deciding to create it:

1. **New worker endpoint** `:thread-api/get-page-refs-count-by-name`
   (db_worker.cljs) resolves the page name against the worker db. If the page
   exists as a materialized entity, it uses the normal `get-block-refs-count`.
   If the page is a "ghost" (referenced-only, no entity), it scans `:block/title`
   datoms for `[[page-name]]` matches to detect dangling references.

2. **Frontend `<page-refs-count`** (async.cljs) calls this endpoint.

3. **page-aux init** (page.cljs) gates creation on `refs-count > 0`:
   - If positive → create via `page-handler/<create!` with `{:redirect? false
     :reference? true}`, then re-fetch. The ghost page renders normally.
   - If 0 → leave the page nil. `page-inner`'s built-in "Page not found"
     fallback renders (the render gate was also changed from `(when (and page
     (not loading?)) ...)` to `(when-not loading? ...)` so the fallback is
     reachable for nil pages).

4. **Nil-safety guard**: `<get-block-refs-count` asserts `(integer? eid)`;
   when `page-block` is nil, `page-id` is nil, so the init code now guards
   with `(when (and (some? page-id) ...))` to avoid an assertion error that
   would silently kill the promise chain.

**Result:** `referencedPage` went from 3/3 blank (15 s timeout) to 0 blank,
openMs ~255 ms (same as a normal page). Typo pages now show "Page not found"
instead of being silently persisted. See `benchmark-large-page.md`.

### Known limitation (pre-existing, out of scope)

The "Linked references" panel renders empty/collapsed even after the fix.
This is **not** caused by the fix — `window.logseq.api.get_page_linked_references`
returns `[]` even for a **pre-created** page that has `[[...]]` backlinks, with
the fix entirely absent (verified by reverting). It is a separate data/query
issue in this minimal fork's file-graph backend and is out of scope for this
benchmark/optimization task. The fix restores page *rendering*; the
linked-references *data* population is a distinct concern.
