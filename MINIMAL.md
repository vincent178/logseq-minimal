# Logseq OG — Minimal Build Contract

This fork is a **local-first, file-based, account-free** build of Logseq.
The guiding principle: *reliable and easy to support beats feature-rich.*

## Feature contract

### Always in
- Local **file-based** graphs (markdown / org on disk)
- Desktop (Electron) — primary target
- Core editing, linking, journaling, querying
- Local persistence only — no data ever leaves the machine

### Explicitly out
- ❌ **Sync** — no RTC (`worker/rtc`, `handler/db_based/rtc*`), no legacy
  `file_sync`, no remote graphs
- ❌ **DB graphs** — no Datascript-backed graph storage, no DB migrations,
  no DB-graph import/export. File-based markdown/org only.
- ❌ **Login / accounts** — no `user/login`, no token restore, no auth UI,
  no e2ee password flows
- ❌ **Mobile clients** — no iOS/Android (Capacitor) apps, no native shells,
  no `src/main/mobile`, no `frontend.mobile.*` namespaces, no `@capacitor/*`
  deps. Desktop (Electron) + web only. All desktop call sites have been
  refactored to drop native-platform checks entirely.
- ❌ **Flashcards (SRS/FSRS)** — no spaced-repetition engine, no card review
  UI, no cloze flashcard hooks. Existing `#card` blocks remain plain blocks.
- ❌ **Whiteboards (tldraw)** — no whiteboard UI, routes, shortcuts, worker/db
  plumbing, or the `packages/tldraw` workspace. Existing `.edn` whiteboard
  files remain ordinary files on disk; the graph-parser still parses them as
  inert data (never modified or migrated) so user graphs keep loading.
- ❌ **Excalidraw** — no draw extension, no `:excalidraw`/`:tldraw` shadow-cljs
  modules, no `@excalidraw` dependency.
- ❌ **Zotero** — no Zotero extension, settings, routes, or slash commands.
- ❌ **Telemetry / usage diagnostics** — no PostHog, no Sentry, no
  `frontend.modules.instrumentation`, no usage-diagnostics settings row, no
  `@sentry/*`/`posthog-js` deps. Nothing phones home; `:capture-error` events
  only log locally.
- ❌ **Local HTTP API server** — no fastify server in Electron, no `/mcp`
  routes, no MCP CLI (`mcp-server`/`append` commands), no server settings row
  or header indicator, no fastify/`@modelcontextprotocol`/zod deps.
- ❌ **Onboarding UX** — no quick tour (Shepherd), no handbooks panel, no
  onboarding components, no handbook deep-link route (`logseq://handbook`),
  no right-sidebar help links section.
- ❌ **Dev-only RTC/DB leftovers** — no `:dev/rtc-start`, `:dev/rtc-stop`, or
  `:dev/replace-graph-with-db-file` commands.
- ❌ Anything requiring a Logseq server

## Removed parsing code
The following parsing code has been removed entirely (not kept as inert):
- `graph-parser/whiteboard.cljs` + `extract-whiteboard-edn` — old `.edn`
  whiteboard files are silently skipped by `filter-files` (they remain on
  disk but are not parsed into the DB)
- `shape-block?` / `whiteboard?` entity predicates — no longer reachable
  since whiteboard pages are not parsed
- `:logseq.property.fsrs/*` and `:logseq.property.tldraw/*` property defs,
  `:logseq.class/Card`/`:logseq.class/Cards`/`:logseq.class/Whiteboard`
  class defs — removed from built-in schema; old `#card` blocks parse as
  ordinary blocks with user-defined properties
- `graph-parser/exporter.cljs` (entire ns) — DB-graph export logic unused
  in this file-only build; `safe-sanitize-file-name` moved to
  `frontend.util` (its only consumer was pdf/assets.cljs)
- `common-config/draw?` + `default-draw-directory` — excalidraw link
  special-casing removed from mldoc/block parsing

## Why this matters for reliability
- Removing DB graphs transitively removes RTC, e2ee, vector search, and the
  migration engine — the largest sources of complexity and upstream churn.
- With no server dependency, there is no auth/session/network failure mode.
- Every feature cut is one less subsystem to maintain and one less class of bug.

## Invariant
`bb dev:lint-and-test` must stay green after every removal commit.
Each subsystem is removed in its own revertable commit.

## Upstream policy
Cherry-pick upstream **bugfixes only**. Never merge upstream features.
