import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { PGlite } from '@electric-sql/pglite'
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto'

const root = path.resolve(import.meta.dirname, '../..')

export async function createDb() {
  const db = new PGlite({ extensions: { pgcrypto } })
  await db.exec(readFileSync(path.join(root, 'tests/db/stubs.sql'), 'utf8'))
  const dir = path.join(root, 'supabase/migrations')
  for (const file of readdirSync(dir).filter((f) => f.endsWith('.sql')).sort()) {
    try {
      await db.exec(readFileSync(path.join(dir, file), 'utf8'))
    } catch (e) {
      throw new Error(`Migration ${file} failed: ${(e as Error).message}`)
    }
  }
  // Supabase grants table privileges to the API roles by default
  await db.exec(`
    grant all on all tables in schema public to authenticated, service_role;
    grant all on all sequences in schema public to authenticated, service_role;
  `)
  return db
}

export type Db = PGlite

/** Run fn as an authenticated Supabase user (RLS applies). Pass null for anon. */
export async function asUser<T>(db: Db, userId: string | null, fn: () => Promise<T>): Promise<T> {
  const claims = userId ? JSON.stringify({ sub: userId, role: 'authenticated' }) : ''
  await db.query(`select set_config('request.jwt.claims', $1, false)`, [claims])
  await db.exec(`set role ${userId ? 'authenticated' : 'anon'}`)
  try {
    return await fn()
  } finally {
    await db.exec('reset role')
    await db.query(`select set_config('request.jwt.claims', '', false)`)
  }
}

/** Run fn as the service role (bypasses RLS) — what Edge Functions use. */
export async function asService<T>(db: Db, fn: () => Promise<T>): Promise<T> {
  await db.query(`select set_config('request.jwt.claims', $1, false)`, [JSON.stringify({ role: 'service_role' })])
  await db.exec('set role service_role')
  try {
    return await fn()
  } finally {
    await db.exec('reset role')
    await db.query(`select set_config('request.jwt.claims', '', false)`)
  }
}

export async function one<T = Record<string, unknown>>(db: Db, sql: string, params: unknown[] = []): Promise<T> {
  const res = await db.query<T>(sql, params)
  return res.rows[0]
}

export async function createUser(db: Db, email: string, name: string, appMeta: Record<string, unknown> = {}) {
  const row = await one<{ id: string }>(
    db,
    `insert into auth.users (email, raw_user_meta_data, raw_app_meta_data) values ($1, $2, $3) returning id`,
    [email, JSON.stringify({ full_name: name }), JSON.stringify(appMeta)],
  )
  return row.id
}

/** Create a lender account for an existing login the way the admin console does (service role). */
export async function createOrganization(db: Db, ownerId: string, p: Record<string, unknown>) {
  const row = await asService(db, () => one<{ r: { organization_id: string } }>(
    db, `select public.lf_platform_create_organization($1::jsonb, $2, null) as r`, [JSON.stringify(p), ownerId]))
  return row.r.organization_id
}
