// Vite replacement for the gulp build (minus electron orchestration):
//
//   gulp syncResourceFile   -> viteStaticCopy target 'resources/**'
//   gulp syncAssetFiles     -> viteStaticCopy targets below (node_modules UMD
//                              assets, wasm, fonts, mobile mirrors)
//   gulp css.buildCSS/watch -> this config's CSS entry (tailwind.all.css),
//                              processed by Vite's built-in PostCSS pipeline
//                              reusing postcss.config.js (autoprefixer,
//                              postcss-import-ext-glob, postcss-import,
//                              postcss-functions, postcss-nested, tailwind,
//                              cssnano in production)
//   gulp switchReactDevelopmentMode -> reactDevMode plugin (watch only)
//
// Differences vs. gulp, by design:
// - CSS url() references (KaTeX/Inter/tabler fonts, pdfjs images) are now
//   resolved and emitted by Vite into static/css/assets/ with hashed names,
//   and style.css points at them. The old verbatim copies into
//   static/css/fonts and static/css/Inter (web)/ are therefore dropped for
//   the main app (nothing referenced them except style.css url()s). This
//   also fixes previously-broken pdfjs `images/*` urls, at the cost of
//   emitting every referenced font format (ttf/woff/eot in addition to the
//   woff2 the gulp build shipped).
// - @excalidraw/excalidraw is not a dependency anymore; the old gulp copy of
//   excalidraw-assets was already a no-op and is dropped.
import fs from 'node:fs'
import path from 'node:path'
import chokidar from 'chokidar'
import { defineConfig } from 'vite'
import { glob } from 'tinyglobby'
import { viteStaticCopy } from 'vite-plugin-static-copy'

const isWatch = process.argv.includes('--watch')
const outputJsPath = 'static/js'

/**
 * Expand `@import-glob "<pattern>"` into plain `@import` statements.
 *
 * Vite prepends its own postcss-import instance ahead of the user plugins
 * from postcss.config.js, so `postcss-import-ext-glob` runs too late: the
 * expanded @imports end up after inlined content and get dropped ("@import
 * must precede all other statements"). Expanding the glob before Vite's
 * postcss-import runs keeps the exact semantics of postcss-import-ext-glob
 * (absolute paths, ascending sort).
 */
const expandImportGlob = () => ({
  name: 'expand-import-glob',
  enforce: 'pre',
  async transform (code, id) {
    if (!id.endsWith('tailwind.all.css') || !code.includes('@import-glob')) {
      return null
    }
    const dir = path.dirname(id)
    const lines = []
    for (const line of code.split('\n')) {
      const m = line.match(/^\s*@import-glob\s+"([^"]+)"\s*;\s*$/)
      if (!m) {
        lines.push(line)
        continue
      }
      const entries = (await glob(path.posix.join(dir, m[1]))).sort()
      for (const entry of entries) lines.push(`@import "${entry}";`)
    }
    return { code: lines.join('\n'), map: null }
  },
})

/** UMD scripts copied verbatim to static/js (referenced by resources/index.html). */
const umdScripts = [
  'node_modules/katex/dist/katex.min.js',
  'node_modules/katex/dist/contrib/mhchem.min.js',
  'node_modules/html2canvas/dist/html2canvas.min.js',
  'node_modules/interactjs/dist/interact.min.js',
  'node_modules/photoswipe/dist/umd/*.js',
  'node_modules/shepherd.js/dist/js/shepherd.min.js',
  'node_modules/marked/marked.min.js',
  'node_modules/@highlightjs/cdn-assets/highlight.min.js',
  'node_modules/@isomorphic-git/lightning-fs/dist/lightning-fs.min.js',
  'packages/ui/dist/ui.js',
  // loaded by the db-worker bundle via new URL('sqlite3.wasm', self.location.href)
  'node_modules/@sqlite.org/sqlite-wasm/sqlite-wasm/jswasm/sqlite3.wasm',
  // spawned by the sqlite-wasm glue as the OPFS async-proxy Worker
  // (webpack used to emit it as a chunk; we ship the raw classic script)
  'node_modules/@sqlite.org/sqlite-wasm/sqlite-wasm/jswasm/sqlite3-opfs-async-proxy.js',
  'node_modules/react/umd/react.production.min.js',
  'node_modules/react/umd/react.development.js',
  'node_modules/react-dom/umd/react-dom.production.min.js',
  'node_modules/react-dom/umd/react-dom.development.js',
  'node_modules/prop-types/prop-types.min.js',
  'node_modules/dompurify/dist/purify.js',
]

/**
 * gulp keepSyncResourceFile equivalent: vite-plugin-static-copy only re-copies
 * on rebuild (writeBundle), and in `vite build --watch` its copy sources are
 * not watched (its own watcher only serves `vite serve`), so editing a
 * resources file would not propagate until an unrelated rebuild. Mirror
 * resources/ changes directly into static/ instead.
 */
const watchResources = () => {
  let watcher
  return {
    name: 'watch-resources',
    apply: () => isWatch,
    buildStart () {
      if (watcher) return
      const srcRoot = path.resolve('resources')
      const destRoot = path.resolve('static')
      const destFor = (p) => path.join(destRoot, path.relative(srcRoot, p))
      const copy = (p) => {
        const dest = destFor(p)
        fs.mkdirSync(path.dirname(dest), { recursive: true })
        fs.copyFileSync(p, dest)
      }
      watcher = chokidar
        .watch(srcRoot, { ignoreInitial: true })
        .on('add', copy)
        .on('change', copy)
        .on('addDir', (p) => {
          fs.cpSync(p, destFor(p), { recursive: true })
        })
        .on('unlink', (p) => fs.rmSync(destFor(p), { force: true }))
        .on('unlinkDir', (p) => fs.rmSync(destFor(p), { recursive: true, force: true }))
    },
  }
}

/** gulp switchReactDevelopmentMode: in dev watch, swap in development React. */
const reactDevMode = () => ({
  name: 'react-dev-mode',
  apply: () => isWatch,
  closeBundle () {
    for (const [from, to] of [
      ['react.development.js', 'react.production.min.js'],
      ['react-dom.development.js', 'react-dom.production.min.js'],
    ]) {
      const src = path.join(outputJsPath, from)
      const dest = path.join(outputJsPath, to)
      try {
        fs.renameSync(src, dest)
      } catch (err) {
        if (err.code !== 'ENOENT') throw err
      }
    }
  },
})

export default defineConfig({
  // relative base so rewritten CSS url()s work over file:// (Electron)
  base: './',
  build: {
    outDir: 'static',
    emptyOutDir: false,
    sourcemap: false,
    // keep dev-watch CSS readable; the postcss-cli watch output was unminified
    cssMinify: isWatch ? false : undefined,
    rollupOptions: {
      input: 'tailwind.all.css',
      output: {
        assetFileNames: (assetInfo) => {
          const name = (assetInfo.names ?? [assetInfo.name])[0] ?? ''
          if (name.endsWith('.css')) return 'css/style.css'
          return 'css/assets/[name]-[hash][extname]'
        },
      },
    },
  },
  plugins: [
    expandImportGlob(),
    viteStaticCopy({
      targets: [
        // syncResourceFile: resources/** -> static/.
        // NOTE: `structured` is a plugin-level option in
        // vite-plugin-static-copy (target-level is silently ignored), and
        // flatten mode would copy nested files to the output root in
        // addition to their subtree. Matching only immediate children
        // (directories copy recursively) keeps the tree intact.
        { src: 'resources/*', dest: '.' },
        // syncAssetFiles: node_modules UMD/wasm assets
        { src: umdScripts, dest: 'js' },
        {
          // gulp-replace: rewrite the UMD global to `tablerIcons` on `React`
          src: 'node_modules/@tabler/icons-react/dist/umd/tabler-icons-react.min.js',
          dest: 'js',
          transform: (content) =>
            content.toString().replace(
              '"@tabler/icons-react"]={},a.react,',
              '"tablerIcons"]={},a.React,'),
        },
        {
          src: [
            'node_modules/@glidejs/glide/dist/glide.min.js',
            'node_modules/@glidejs/glide/dist/css/glide.core.min.css',
            'node_modules/@glidejs/glide/dist/css/glide.theme.min.css',
          ],
          dest: 'js/glide',
        },
        {
          src: [
            'node_modules/pdfjs-dist/legacy/build/pdf.mjs',
            'node_modules/pdfjs-dist/legacy/build/pdf.worker.mjs',
            'node_modules/pdfjs-dist/legacy/web/pdf_viewer.mjs',
          ],
          dest: 'js/pdfjs',
        },
        { src: 'node_modules/pdfjs-dist/cmaps/*.*', dest: 'js/pdfjs/cmaps' },
        // mobile mirrors (kept verbatim from the gulp setup)
        {
          src: [
            'node_modules/katex/dist/katex.min.js',
            'node_modules/katex/dist/contrib/mhchem.min.js',
            'node_modules/marked/marked.min.js',
            'node_modules/@highlightjs/cdn-assets/highlight.min.js',
            'node_modules/@isomorphic-git/lightning-fs/dist/lightning-fs.min.js',
            'node_modules/react/umd/react.production.min.js',
            'node_modules/react/umd/react.development.js',
            'node_modules/react-dom/umd/react-dom.production.min.js',
            'node_modules/react-dom/umd/react-dom.development.js',
            'node_modules/prop-types/prop-types.min.js',
            'node_modules/interactjs/dist/interact.min.js',
            'node_modules/photoswipe/dist/umd/*.js',
            'packages/ui/dist/ui.js',
            'node_modules/@sqlite.org/sqlite-wasm/sqlite-wasm/jswasm/sqlite3.wasm',
          ],
          dest: 'mobile/js',
        },
        { src: 'node_modules/inter-ui/inter.css', dest: 'mobile/css' },
        {
          // parens/spaces must be escaped for tinyglobby
          src: 'node_modules/inter-ui/Inter\\ \\(web\\)/*.*',
          dest: 'mobile/css/Inter (web)',
        },
        {
          src: [
            'node_modules/@tabler/icons-webfont/fonts/**',
            'node_modules/katex/dist/fonts/*.woff2',
          ],
          dest: 'mobile/css/fonts',
        },
      ],
    }),
    watchResources(),
    reactDevMode(),
  ],
})
