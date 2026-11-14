// Concatenate supabase/migrations/*.sql into one file for the Supabase SQL Editor.
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'

const dir = path.resolve(import.meta.dirname, '../supabase/migrations')
const out = path.resolve(import.meta.dirname, '../supabase/deploy/lendflow_all_migrations.sql')
const files = readdirSync(dir).filter((f) => f.endsWith('.sql')).sort()
let sql = '-- LendFlow: all migrations bundled for the Supabase SQL Editor (generated; do not edit)\n'
for (const f of files) sql += `\n-- ==== ${f} ====\n${readFileSync(path.join(dir, f), 'utf8')}\n`
mkdirSync(path.dirname(out), { recursive: true })
writeFileSync(out, sql)
console.log(`Bundled ${files.length} migrations -> ${path.relative(process.cwd(), out)}`)
