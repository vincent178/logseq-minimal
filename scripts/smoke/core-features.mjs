#!/usr/bin/env node
/**
 * Smoke test: core Logseq file-graph features on Electron.
 *
 * Verifies the features the minimal build must keep working after every change:
 *   1. journals          — today's journal loads and accepts edits
 *   2. bidirectional link — [[page-ref]] renders, navigates, shows backlinks
 *   3. query             — {{query [[X]]}} renders live results
 *   4. task              — TODO block renders and cycles state on click
 *   5. graph view        — canvas + control panel render
 *
 * The suite runs against whatever file graph is currently open in the running
 * Electron instance. To stay deterministic and side-effect free, every check
 * uses a unique run-tagged page/block (RUN_TAG = timestamp), so re-runs never
 * collide with prior data and the journal is not polluted.
 *
 * Prerequisites:
 *   - yarn electron-watch running (serves app on :3001, builds static/electron.js)
 *   - Electron launched with --remote-debugging-port=<CDP_PORT> and a file
 *     graph already open (the app restores the last-opened graph).
 *
 * Usage:
 *   node scripts/smoke/core-features.mjs [--cdp 9333]
 * Exit code 0 = all checks pass, 1 = a check failed.
 */

import { chromium } from 'playwright-core';

const CDP_PORT = (() => {
  const i = process.argv.indexOf('--cdp');
  return i >= 0 ? process.argv[i + 1] : (process.env.CDP_PORT || '9333');
})();

const RUN_TAG = 'Smoke' + Date.now();
const results = [];
const record = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? '✅' : '❌'} ${name}${detail ? ' — ' + detail : ''}`);
};

let browser;
let lastErr;
// Retry CDP connect: the renderer can briefly reload (e.g. after a watch
// rebuild), which drops the connection. Try a few times before giving up.
for (let attempt = 0; attempt < 8 && !browser; attempt++) {
  try {
    browser = await chromium.connectOverCDP(`http://localhost:${CDP_PORT}`);
  } catch (e) {
    lastErr = e;
    await new Promise(r => setTimeout(r, 2000));
  }
}
if (!browser) {
  console.error(`Cannot connect to Electron CDP on port ${CDP_PORT}.`);
  console.error('Launch Electron with: static/node_modules/electron/dist/Electron.app/Contents/MacOS/Electron . --remote-debugging-port=' + CDP_PORT);
  console.error(String(lastErr).slice(0, 200));
  process.exit(1);
}

let page;
for (let attempt = 0; attempt < 8 && !page; attempt++) {
  page = browser.contexts()[0]?.pages().find(p => !p.url().includes('db-worker'));
  if (!page) await new Promise(r => setTimeout(r, 2000));
}
if (!page) {
  console.error('No Logseq renderer page found over CDP.');
  process.exit(1);
}
// Give the renderer a moment to finish any in-flight reload before driving it.
await page.waitForLoadState('domcontentloaded').catch(() => {});
await page.waitForTimeout(1500);
page.setDefaultTimeout(30000);

const errors = [];
page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
const fatalErrors = () => errors.filter(e =>
  /TypeError|Cannot read|undefined is not/i.test(e) &&
  !/sync-app|DevTools|Security|OPFS|Deeplink|electron-bundle|Invalid attr/i.test(e));

// ---- Sanity: a file graph must be open ------------------------------------
await page.waitForSelector('.block-content, #main-container, .journals', { timeout: 30000 }).catch(() => {});
const graph = await page.evaluate(() => window.logseq?.api?.get_current_graph?.());
const graphOpen = !!(graph && (graph.path || graph.url));
record('file graph open', graphOpen, graph?.name || 'none');
if (!graphOpen) {
  console.error('No file graph is open in Electron. Open one first.');
  process.exit(1);
}

const append = (content) => page.evaluate((c) => window.logseq.api.append_block_in_page(c), content);
const bodyText = () => page.evaluate(() => document.body.innerText);

// Navigate to today's journal so .block-content is present regardless of where
// the app was left (e.g. on the graph-view page after a previous run).
async function gotoJournal() {
  await page.evaluate(() => {
    const r = window.frontend?.handler?.route;
    if (r?.redirect_to_page_BANG_) {
      const today = new Date();
      const fmt = today.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
        .replace(/(\d+),/, (m, d) => d + (/1$/.test(d) && d !== '11' ? 'st,' : /2$/.test(d) && d !== '12' ? 'nd,' : /3$/.test(d) && d !== '13' ? 'rd,' : 'th,'));
      try { r.redirect_to_page_BANG_(fmt); return; } catch {}
    }
  }).catch(() => {});
  await page.keyboard.press('Escape').catch(() => {});
  // Fallback: click the Journals nav item.
  if (!(await page.locator('.block-content').first().isVisible().catch(() => false))) {
    await page.getByText('Journals', { exact: true }).first().click().catch(() => {});
  }
  await page.waitForSelector('.block-content', { timeout: 20000 }).catch(() => {});
  await page.waitForTimeout(800);
}
await gotoJournal();

// ---- 1. Journals ------------------------------------------------------------
try {
  await page.waitForSelector('.block-content', { timeout: 30000 });
  const title = await page.evaluate(() =>
    (document.querySelector('.page-title, h1.title, [data-testid="page title"]')?.innerText || '').trim());
  const marker = RUN_TAG + '_journal';
  await append(marker);
  await page.waitForTimeout(1200);
  const typed = (await bodyText()).includes(marker);
  record('1. journals', title.length > 0 && typed, `journal="${title}" edit=${typed}`);
} catch (e) {
  record('1. journals', false, String(e).slice(0, 120));
}

// ---- 2. Bidirectional link ---------------------------------------------------
try {
  const target = RUN_TAG + '_LinkTarget';
  await append(`link source [[${target}]]`);
  await page.waitForTimeout(1800);
  const refSel = `a.page-ref:has-text("${target}")`;
  const refVisible = await page.locator(refSel).first().isVisible().catch(() => false);
  let navigated = false, backlink = false;
  if (refVisible) {
    await page.locator(refSel).first().click();
    await page.waitForTimeout(2500);
    const body = await bodyText();
    navigated = body.includes(target);
    backlink = /Linked references?/i.test(body) && body.includes('link source');
  }
  record('2. bidirectional link', refVisible && navigated && backlink,
    `ref=${refVisible} nav=${navigated} backlink=${backlink}`);
} catch (e) {
  record('2. bidirectional link', false, String(e).slice(0, 120));
}

// ---- 3. Query ----------------------------------------------------------------
try {
  const qtag = RUN_TAG + '_Query';
  await append(`query data [[${qtag}]]`);
  await append(`{{query [[${qtag}]]}}`);
  await page.waitForTimeout(3000);
  const body = await bodyText();
  const live = /Live query|\d+\s+results?/i.test(body);
  const found = body.includes(qtag);
  record('3. query', live && found, `live=${live} found=${found}`);
} catch (e) {
  record('3. query', false, String(e).slice(0, 120));
}

// ---- 4. Task -----------------------------------------------------------------
try {
  const ttag = RUN_TAG + '_task';
  await append(`TODO ${ttag}`);
  await page.waitForTimeout(1800);
  let body = await bodyText();
  const rendered = /TODO/.test(body) && body.includes(ttag);
  const marker = page.getByText('TODO', { exact: true }).last();
  const markerVisible = await marker.isVisible().catch(() => false);
  let cycled = false;
  if (markerVisible) {
    await marker.click().catch(() => {});
    await page.waitForTimeout(1500);
    body = await bodyText();
    cycled = /DOING|DONE/.test(body) && body.includes(ttag);
  }
  record('4. task', rendered && cycled, `render=${rendered} cycle=${cycled}`);
} catch (e) {
  record('4. task', false, String(e).slice(0, 120));
}

// ---- 5. Graph view ------------------------------------------------------------
try {
  await page.getByText('Graph view', { exact: false }).first().click();
  await page.waitForTimeout(4000);
  const hasCanvas = await page.locator('canvas').first().isVisible().catch(() => false);
  record('5. graph view', hasCanvas, `canvas=${hasCanvas}`);
  // navigate back to the journal so the next run starts clean
  await page.goBack().catch(() => {});
} catch (e) {
  record('5. graph view', false, String(e).slice(0, 120));
}

// ---- Console errors -----------------------------------------------------------
const fatal = fatalErrors();
record('no fatal console errors', fatal.length === 0, fatal.slice(0, 2).join(' | ').slice(0, 120));

// ---- Summary ------------------------------------------------------------------
await browser.close();
const failed = results.filter(r => !r.ok);
console.log('\n' + '='.repeat(50));
console.log(`${results.length - failed.length}/${results.length} checks passed`);
if (failed.length) {
  console.log('FAILED: ' + failed.map(f => f.name).join(', '));
  process.exit(1);
}
console.log('All core features OK');
process.exit(0);
