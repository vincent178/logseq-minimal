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
- ❌ Anything requiring a Logseq server

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
