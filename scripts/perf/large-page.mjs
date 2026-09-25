#!/usr/bin/env node
/**
 * Performance / correctness benchmark: large pages in the Logseq browser dev app.
 *
 * Discovered via profiling (see docs/dev/benchmark-large-page.md): a plain
 * 1,000-block page renders fine (the app virtualizes ≥10 top-level blocks),
 * but a *large referenced page* — one that is the target of many backlinks and
 * has no explicit page entity — renders BLANK (nothing mounts, no error, no
 * timeout). This harness measures both the healthy render path and the blank
 * failure so a fix can be validated against a hard baseline.
 *
 * Metrics (per scenario, per run):
 *   - openMs            : route-nav → page's own blocks visible (or "Page not found")
 *   - blank             : true if nothing rendered within the timeout (the bug)
 *   - keystrokeMs       : Enter → new empty textarea focused (p50)
 *   - charInsertMs      : keystroke → char visible in textarea value (p50)
 *   - longTasks         : >50ms tasks on the main thread during open
 *   - domBlocks         : .ls-block nodes in DOM (virtualization keeps this small)
 *
 * Methodology highlights (docs/dev/benchmark-large-page.md):
 *   - Playwright launches system Chrome (channel:'chrome'), one fresh incognito
 *     context per run (cold caches), deterministic 1440x900 viewport.
 *   - The app boots standalone on :3001 with the in-memory "Demo" graph (same
 *     path the e2e suite uses), so no native directory picker is needed.
 *   - Pages are seeded via window.logseq.api (create_page / insert_batch_block),
 *     NOT through the editor, so seeding cost is isolated from render cost.
 *   - Navigation uses same-document `location.hash` routing (the app's router),
 *     and "rendered" = route-OWNED content present (not stale DOM from the
 *     previous page), which avoids false-positive open times.
 *
 * Prereq: `yarn electron-watch` (or `yarn watch`) serving the app on :3001.
 *
 * Usage:
 *   node scripts/perf/large-page.mjs [--runs 3] [--blocks 1000] [--timeout 15000]
 *   node scripts/perf/large-page.mjs --out docs/dev/benchmark-large-page.json
 * Exit 0 always (reports results); non-zero only if the app is unreachable.
 */

import { chromium } from 'playwright-core';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const arg = (name, dflt) => {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : dflt;
};
const RUNS = Number(arg('runs', 3));
const BLOCKS = Number(arg('blocks', 1000));
const REFS = Number(arg('refs', 50));
const TIMEOUT = Number(arg('timeout', 15000));
const OUT = arg('out', null);
const APP_URL = arg('url', 'http://localhost:3001');

const pct = (a, p) => { if (!a.length) return 0; const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(s.length * p))]; };

// ---- fixture builders (run in-page) --------------------------------------
const seedPlain = async ({ name, n }) => {
  await window.logseq.api.create_page(name);
  const b = [];
  for (let i = 0; i < n; i++) b.push({ content: `block ${i} lorem ipsum dolor sit amet consectetur adipiscing elit` });
  await window.logseq.api.insert_batch_block(name, b);
};
const seedReferencers = async ({ srcName, targetName, n }) => {
  await window.logseq.api.create_page(srcName);
  const b = [];
  for (let i = 0; i < n; i++) b.push({ content: `ref ${i} -> [[${targetName}]]` });
  await window.logseq.api.insert_batch_block(srcName, b);
};

// ---- one scenario measurement --------------------------------------------
async function measureOpen(page, target) {
  // navigate via the router (same-document) and detect route-OWNED render
  await page.evaluate((nm) => { window.__navT = performance.now(); location.hash = '#/page/' + encodeURIComponent(nm); }, target);
  const deadline = Date.now() + TIMEOUT;
  let rendered = false;
  while (Date.now() < deadline) {
    await page.waitForTimeout(250);
    const state = await page.evaluate((want) => {
      const mc = document.querySelector('#main-content-container');
      if (!mc) return { ok: false };
      const title = (mc.querySelector('h1.page-title, .ls-page-title h1, h1')?.innerText || '').trim();
      const notFound = /page not found/i.test(mc.innerText || '');
      const blocks = mc.querySelectorAll('.ls-block').length;
      // Route-owned detection must NOT trust `blocks > 0` alone: during a
      // same-document route transition the previous page's .ls-block nodes can
      // still be in the DOM, which would produce a false-positive open time
      // (and mask a blank render) for the next route. Require the page title
      // to match the target (or an explicit not-found state) instead.
      const titleMatch = title && title.toLowerCase() === want.toLowerCase();
      return { ok: Boolean(notFound || titleMatch), blocks, notFound, title };
    }, target);
    if (state.ok) { rendered = true; break; }
  }
  const openMs = Math.round(await page.evaluate(() => performance.now() - window.__navT));
  const dom = await page.evaluate(() => ({
    domBlocks: document.querySelectorAll('#main-content-container .ls-block').length,
    longTasks: (window.__lt || []).length,
    longTaskTotalMs: Math.round((window.__lt || []).reduce((a, b) => a + b, 0)),
  }));
  return { openMs, blank: !rendered, ...dom };
}

async function measureEditing(page) {
  // let the page finish mounting/virtualizing before interacting
  await page.waitForFunction(() => document.querySelectorAll('#main-content-container .ls-block .block-content').length >= 2, null, { timeout: 10000 }).catch(() => {});
  await page.waitForTimeout(500);
  const first = page.locator('#main-content-container .ls-block .block-content').first();
  if (!(await first.count())) return { keystrokeMs: null, charInsertMs: null, domBlocks: 0 };
  const domBlocks = await page.evaluate(() => document.querySelectorAll('#main-content-container .ls-block').length);
  await first.click();
  const opened = await page.waitForSelector('#main-content-container textarea', { timeout: 8000 }).then(() => true).catch(() => false);
  if (!opened) return { keystrokeMs: null, charInsertMs: null, domBlocks };
  // Enter latency: pressing Enter in the editor commits the current block and
  // focuses a new empty one. We detect completion by the active textarea
  // becoming a *different element* (or its value resetting) after the keypress.
  // Prime the editor with a char first so the split is meaningful.
  const enters = [];
  for (let i = 0; i < 5; i++) {
    await page.keyboard.type('x', { delay: 0 });
    await page.waitForFunction(() => { const a = document.activeElement; return a && a.tagName === 'TEXTAREA' && a.value.length > 0; }, null, { timeout: 3000 }).catch(() => {});
    const handle = await page.evaluateHandle(() => document.activeElement);
    const t = Date.now();
    await page.keyboard.press('Enter');
    await page.waitForFunction((prev) => {
      const a = document.activeElement;
      return a && a.tagName === 'TEXTAREA' && a !== prev;
    }, handle, { timeout: 3000 }).catch(() => {});
    enters.push(Date.now() - t);
    await handle.dispose();
  }
  // char-insert latency: keystroke -> char reflected in textarea value
  const chars = [];
  for (const ch of ['a', 'b', 'c', 'd', 'e']) {
    const t = Date.now();
    await page.keyboard.type(ch, { delay: 0 });
    await page.waitForFunction((c) => { const a = document.activeElement; return a && a.tagName === 'TEXTAREA' && a.value.includes(c); }, ch, { timeout: 8000 }).catch(() => {});
    chars.push(Date.now() - t);
  }
  await page.keyboard.press('Escape');
  return { keystrokeMs: pct(enters, 0.5), charInsertMs: pct(chars, 0.5), domBlocks };
}

async function oneRun() {
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  try {
    const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
    const page = await ctx.newPage();
    page.setDefaultTimeout(60000);
    await page.goto(APP_URL, { waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => window.logseq?.api, null, { timeout: 60000 });
    await page.waitForSelector('#main-content-container .ls-block', { timeout: 60000 });
    // longtask observer on the live document
    await page.evaluate(() => {
      window.__lt = [];
      try { new PerformanceObserver((l) => { for (const e of l.getEntries()) if (e.entryType === 'longtask') window.__lt.push(e.duration); }).observe({ entryTypes: ['longtask'] }); } catch (_) {}
    });

    const ts = Date.now();
    const PLAIN = `bench-plain-${ts}`;
    const SRC = `bench-src-${ts}`;
    const GHOST = `bench-ghost-${ts}`;   // referenced-only, never explicitly created

    // Scenario A: large plain page (baseline render path)
    await page.evaluate(seedPlain, { name: PLAIN, n: BLOCKS });
    const a = await measureOpen(page, PLAIN);
    const editA = a.blank ? { keystrokeMs: null, charInsertMs: null } : await measureEditing(page);
    // domBlocks from the settled editing pass is more reliable than open-time
    if (editA.domBlocks) a.domBlocks = editA.domBlocks;

    // Scenario B: large referenced page (the blank-bug scenario)
    await page.evaluate(seedReferencers, { srcName: SRC, targetName: GHOST, n: REFS });
    const b = await measureOpen(page, GHOST);

    // cleanup
    for (const p of [PLAIN, SRC, GHOST]) await page.evaluate(async (nm) => { try { await window.logseq.api.delete_page(nm); } catch (_) {} }, p);

    return {
      largePage: { ...a, ...editA },
      referencedPage: { ...b, refs: REFS },
    };
  } finally {
    await browser.close();
  }
}

// ---- main -----------------------------------------------------------------
if (!/localhost|127\.0\.0\.1/.test(APP_URL)) { console.error(`Refusing non-local APP_URL: ${APP_URL}`); process.exit(1); }

const results = [];
for (let r = 0; r < RUNS; r++) {
  process.stdout.write(`run ${r + 1}/${RUNS} ... `);
  const m = await oneRun();
  results.push(m);
  console.log(`largeOpen=${m.largePage.openMs}ms(blank=${m.largePage.blank}) referencedOpen=${m.referencedPage.openMs}ms(blank=${m.referencedPage.blank})`);
}

const med = (sel) => pct(results.map(sel), 0.5);
const summary = {
  meta: { date: new Date().toISOString(), blocks: BLOCKS, refs: REFS, runs: RUNS, timeout: TIMEOUT, appUrl: APP_URL, host: os.hostname() },
  largePage: {
    openMs: { median: med((r) => r.largePage.openMs), runs: results.map((r) => r.largePage.openMs) },
    blankRuns: results.filter((r) => r.largePage.blank).length,
    keystrokeMs: med((r) => r.largePage.keystrokeMs || 0),
    charInsertMs: med((r) => r.largePage.charInsertMs || 0),
    domBlocks: med((r) => r.largePage.domBlocks),
    longTasks: med((r) => r.largePage.longTasks),
  },
  referencedPage: {
    openMs: { median: med((r) => r.referencedPage.openMs), runs: results.map((r) => r.referencedPage.openMs) },
    blankRuns: results.filter((r) => r.referencedPage.blank).length,
    domBlocks: med((r) => r.referencedPage.domBlocks),
  },
};
console.log('\n=== summary ===');
console.log(JSON.stringify(summary, null, 2));
if (OUT) { fs.mkdirSync(path.dirname(path.resolve(OUT)), { recursive: true }); fs.writeFileSync(OUT, JSON.stringify(summary, null, 2) + '\n'); console.log(`written -> ${OUT}`); }
process.exit(0);
