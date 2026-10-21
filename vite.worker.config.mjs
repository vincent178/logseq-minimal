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
// - `process` is injected from process/browser, mirroring webpack's
//   ProvidePlugin. The inject plugin must run with enforce 'post': if it
//   runs before Vite's commonjs conversion, CJS modules that reference
//   `process` (e.g. path.js) become mixed ES/CJS modules whose named
//   exports silently resolve to `undefined`.
import { defineConfig } from 'vite'
import inject from '@rollup/plugin-inject'

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

/** process injection (webpack ProvidePlugin equivalent), post-commonjs. */
const injectProcess = () =>
  Object.assign(inject({ process: 'process/browser' }), { enforce: 'post' })

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
