// Replaces `gulp electronMaker`: release-compile the electron build, sync the
// app version into static/package.json, then package with electron-forge.
import cp from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve(import.meta.dirname, '..')
const outputPath = path.join(root, 'static')

cp.execSync('yarn cljs:release-electron', { cwd: root, stdio: 'inherit' })

const pkgPath = path.join(outputPath, 'package.json')
const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'))
const version = fs
  .readFileSync(path.join(root, 'src/main/frontend/version.cljs'))
  .toString()
  .match(/[0-9.]{3,}/)[0]

if (!version) {
  throw new Error('release version error in src/**/*/version.cljs')
}

pkg.version = version
fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2))

if (!fs.existsSync(path.join(outputPath, 'node_modules'))) {
  cp.execSync('yarn', { cwd: outputPath, stdio: 'inherit' })
}

cp.execSync('yarn electron:make', { cwd: outputPath, stdio: 'inherit' })
