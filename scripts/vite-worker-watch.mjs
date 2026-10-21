// `yarn vite:worker-watch`: wait for shadow-cljs to emit the :db-worker
// external index before starting the Vite watcher.
//
// `yarn watch`/`yarn dev` start cljs and vite in parallel, but on a fresh
// checkout target/db-worker.js only appears after the first cljs compile —
// and `vite build --watch` exits immediately when its entry is missing.
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'

const entry = path.resolve(import.meta.dirname, '../target/db-worker.js')

process.stdout.write(`[vite:worker-watch] waiting for ${entry}...\n`)
while (!fs.existsSync(entry)) {
  await new Promise((resolve) => setTimeout(resolve, 1000))
}

const child = spawn(
  process.execPath,
  ['node_modules/vite/bin/vite.js', 'build', '--watch', '--config', 'vite.worker.config.mjs'],
  { stdio: 'inherit' },
)
child.on('exit', (code) => process.exit(code ?? 1))
