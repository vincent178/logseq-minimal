// Replaces `gulp electron`: ensure static/node_modules exists, then run the
// electron app in dev mode.
import cp from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'

const outputPath = path.resolve(import.meta.dirname, '../static')

if (!fs.existsSync(path.join(outputPath, 'node_modules'))) {
  cp.execSync('yarn', { cwd: outputPath, stdio: 'inherit' })
}

cp.execSync('yarn electron:dev', { cwd: outputPath, stdio: 'inherit' })
