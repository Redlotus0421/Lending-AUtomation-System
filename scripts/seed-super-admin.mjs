// Create (or update) a LendFlow platform super admin.
//
//   npm run seed:admin -- you@example.com
//
// The password is typed at a hidden prompt and sent straight to Supabase Auth; it is never
// written to a file. The service-role key is fetched at runtime from the Supabase CLI
// (you must be logged in and the project linked) and kept in memory only.
import { execSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import path from 'node:path'
import readline from 'node:readline'
import { createClient } from '@supabase/supabase-js'

const root = path.resolve(import.meta.dirname, '..')
const MIN_PASSWORD = 10

function fail(msg) {
  console.error(`\n✖ ${msg}`)
  process.exit(1)
}

function ask(question) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
  return new Promise((resolve) => rl.question(question, (a) => { rl.close(); resolve(a.trim()) }))
}

function askHidden(prompt) {
  return new Promise((resolve) => {
    const stdin = process.stdin
    if (!stdin.isTTY) fail('Run this in an interactive terminal so the password can be typed privately.')
    process.stdout.write(prompt)
    stdin.setRawMode(true)
    stdin.resume()
    stdin.setEncoding('utf8')
    let value = ''
    const cleanup = () => { stdin.setRawMode(false); stdin.pause(); stdin.removeListener('data', onData) }
    const onData = (chunk) => {
      for (const c of chunk) {
        if (c === '\r' || c === '\n') { cleanup(); process.stdout.write('\n'); resolve(value); return }
        if (c === '\u0003') { cleanup(); process.stdout.write('\n'); process.exit(130) }
        if (c === '\u007f' || c === '\b') {
          if (value.length) { value = value.slice(0, -1); process.stdout.write('\b \b') }
          continue
        }
        if (c >= ' ') { value += c; process.stdout.write('*') }
      }
    }
    stdin.on('data', onData)
  })
}

function projectRef() {
  const refFile = path.join(root, 'supabase/.temp/project-ref')
  if (existsSync(refFile)) return readFileSync(refFile, 'utf8').trim()
  const env = existsSync(path.join(root, '.env')) ? readFileSync(path.join(root, '.env'), 'utf8') : ''
  const m = env.match(/^VITE_SUPABASE_URL=https:\/\/([a-z0-9]+)\.supabase\.co/m)
  if (m) return m[1]
  fail('Could not find the Supabase project. Run: npx supabase link --project-ref <ref>')
}

function serviceRoleKey(ref) {
  let out
  try {
    out = execSync(`npx supabase projects api-keys --project-ref ${ref} -o json`, { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] }).toString()
  } catch {
    fail('Could not read the project API keys. Log in first with: npx supabase login')
  }
  const keys = JSON.parse(out.slice(out.indexOf('[')))
  const key = keys.find((k) => k.name === 'service_role')?.api_key
  if (!key || key.length < 40) fail('The service_role key is not available for this project.')
  return key
}

async function findUser(admin, email) {
  for (let page = 1; page < 50; page++) {
    const { data, error } = await admin.auth.admin.listUsers({ page, perPage: 1000 })
    if (error) fail(error.message)
    const u = data.users.find((x) => x.email?.toLowerCase() === email)
    if (u) return u
    if (data.users.length < 1000) return null
  }
  return null
}

const email = (process.argv[2] || (await ask('Super admin email: '))).trim().toLowerCase()
if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) fail('Enter a valid email address.')

const ref = projectRef()
console.log(`Project: ${ref}`)
const admin = createClient(`https://${ref}.supabase.co`, serviceRoleKey(ref), { auth: { persistSession: false, autoRefreshToken: false } })

const existing = await findUser(admin, email)
const defaultName = existing?.user_metadata?.full_name || 'Super Admin'
console.log('')
console.log(existing ? `A login for ${email} exists; its password will be replaced.` : `Creating a new login for ${email}.`)
console.log('Step 1 of 2: your display name (shown in the app). This is NOT the password.')
const fullName = (await ask(`  Display name, or press Enter for "${defaultName}": `)) || defaultName
if (fullName.length > 80) fail('The display name is too long.')

console.log('')
console.log('Step 2 of 2: choose the password. Nothing is shown while you type.')
let password = ''
for (let attempt = 1; ; attempt++) {
  const pw = await askHidden(`  Password (min ${MIN_PASSWORD} characters): `)
  let problem = ''
  if (pw.length < MIN_PASSWORD) problem = `That was ${pw.length} characters; use at least ${MIN_PASSWORD}.`
  else if (pw.length > 72) problem = 'Use at most 72 characters.'
  else if ((await askHidden('  Repeat password: ')) !== pw) problem = 'The two passwords did not match.'
  if (!problem) { password = pw; break }
  if (attempt >= 3) fail(problem)
  console.log(`  ${problem} Try again.`)
  console.log('')
}

let userId
if (existing) {
  const { error } = await admin.auth.admin.updateUserById(existing.id, {
    password,
    email_confirm: true,
    user_metadata: { ...existing.user_metadata, full_name: fullName, must_change_password: false },
  })
  if (error) fail(error.message)
  userId = existing.id
} else {
  const { data, error } = await admin.auth.admin.createUser({
    email, password, email_confirm: true, user_metadata: { full_name: fullName },
  })
  if (error) fail(error.message)
  userId = data.user.id
}

const { error: grantError } = await admin.rpc('lf_grant_platform_admin', { p_user: userId })
if (grantError) fail(grantError.message)
await admin.from('user_profiles').update({ full_name: fullName }).eq('id', userId)

console.log(`\n✔ ${email} is a LendFlow super admin.`)
console.log('  Sign in at http://localhost:5173/login (or your deployed URL) to open the admin console.')
process.exit(0)
