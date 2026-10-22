// Vite replacement for webpack.config.js (db-worker bundle only).
//
// Bundles the shadow-cljs :db-worker external index (target/db-worker.js)
// into static/js/db-worker-bundle.js — a classic (IIFE) script loaded by the
// shadow-cljs worker via importScripts('db-worker-bundle.js'). The file name
// is fixed by shadow-cljs' :prepend-js and must not change.
//
// Notes:
// - The bundle runs inside a Web Worker, so `import.meta.url` (which Vite's
//   IIFE builds rewrite to a document.currentScript/document.baseURI shim)
//   is unavailable. The sqlite-wasm glue locates its .wasm file and OPFS
//   proxy worker via `new URL(x, import.meta.url)`; we rewrite those to
//   `self.location.href` (the worker script URL, which always sits next to
//   this bundle in static/js) and ship the referenced files verbatim via the
//   assets build (see vite.assets.config.mjs) — same contract as the old
//   gulp+webpack pipeline.
// - `process` is provided by the injectProcess plugin below (a global
//   process/browser installed before any module body runs), mirroring
//   webpack's ProvidePlugin.
import { defineConfig } from 'vite'

const isWatch = process.argv.includes('--watch')

/**
 * Webpack-compatibility shims for the db-worker bundle.
 *
 * 1. sqlite-wasm glue: rewrite `import.meta.url` → `self.location.href`
 *    (see header comment).
 * 2. target/db-worker.js: webpack's CJS interop makes `import * as x` of a
 *    CommonJS module callable when its export is a function; Rollup's
 *    namespace object is not. shadow-cljs' external index bridges
 *    `remove-accents` via `import * as` and worker-side cljs calls it as a
 *    plain function (frontend.common.search-fuzzy), so unwrap the namespace
 *    to the underlying callable while keeping its extra props (.has/.remove).
 */
const dbWorkerShims = () => ({
  name: 'db-worker-shims',
  enforce: 'pre',
  transform (code, id) {
    if (id.includes('@sqlite.org/sqlite-wasm')) {
      if (!code.includes('import.meta.url')) return null
      return {
        code: code.replaceAll('import.meta.url', 'self.location.href'),
        map: null,
      }
    }
    if (id.endsWith('target/db-worker.js')) {
      const next = code.replace(
        /ALL\["remove-accents"\] = (\w+);/,
        'ALL["remove-accents"] = (typeof $1 === "function" ? $1 : Object.assign($1.default, $1));',
      )
      return next === code ? null : { code: next, map: null }
    }
    return null
  },
})

/**
 * process polyfill injection (webpack ProvidePlugin equivalent), without
 * @rollup/plugin-inject.
 *
 * Instead of editing every module that has a free `process` reference
 * (path.js, util.js, the sqlite glue), we prepend a tiny setup module as the
 * entry's FIRST import, installing process/browser on globalThis. ES module
 * evaluation is depth-first in import order, so the setup module body runs
 * before any other module body — including the ones that touch `process` at
 * top level (`var isWindows = process.platform === 'win32'` in path.js,
 * `if (process.env.NODE_DEBUG)` in util.js). Modules that declare their own
 * local `process` binding are unaffected (the local shadows the global).
 */
const PROCESS_SETUP_ID = '\0db-worker:process-setup'

const injectProcess = () => ({
  name: 'inject-process',
  enforce: 'pre',
  resolveId (id) {
    return id === PROCESS_SETUP_ID ? PROCESS_SETUP_ID : null
  },
  load (id) {
    if (id !== PROCESS_SETUP_ID) return null
    return [
      "import process from 'process/browser'",
      'globalThis.process = globalThis.process || process',
    ].join('\n')
  },
  transform (code, id) {
    if (!id.endsWith('target/db-worker.js')) return null
    return { code: `import ${JSON.stringify(PROCESS_SETUP_ID)};\n${code}`, map: null }
  },
})

export default defineConfig({
  build: {
    outDir: 'static/js',
    emptyOutDir: false,
    // webpack built the bundle with mode "development" under --watch
    minify: !isWatch,
    sourcemap: false,
    lib: {
      entry: 'target/db-worker.js',
      name: 'DbWorkerBundle',
      formats: ['iife'],
      fileName: () => 'db-worker-bundle.js',
    },
    rollupOptions: {
      external: ['react', 'react-dom'],
      output: {
        globals: { react: 'React', 'react-dom': 'ReactDOM' },
      },
    },
  },
  plugins: [
    dbWorkerShims(),
    // fix "process is not defined" (webpack ProvidePlugin equivalent)
    injectProcess(),
  ],
})
