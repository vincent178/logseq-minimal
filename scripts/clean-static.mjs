// Replaces `gulp clean`: empty static/ but keep static/node_modules
// (the electron app's own yarn install lives there).
import fs from 'node:fs'
import path from 'node:path'

const dir = path.resolve(import.meta.dirname, '../static')

// A fresh checkout has no static/ at all (it is fully gitignored) —
// treat a missing directory as already clean.
if (!fs.existsSync(dir)) process.exit(0)

for (const entry of fs.readdirSync(dir)) {
  if (entry === 'node_modules') continue
  fs.rmSync(path.join(dir, entry), { recursive: true, force: true })
}
