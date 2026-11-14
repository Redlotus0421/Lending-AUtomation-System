import { createClient, type SupabaseClient } from 'npm:@supabase/supabase-js@2'

export const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, GET, OPTIONS',
}

export class HttpError extends Error {
  constructor(public status: number, message: string, public code?: string) {
    super(message)
  }
}

export function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { ...corsHeaders, 'Content-Type': 'application/json' } })
}

const SUPABASE_URL = Deno.env.get('SUPABASE_URL')!
const SERVICE_KEY = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!
const ANON_KEY = Deno.env.get('SUPABASE_ANON_KEY')!

export function adminClient(): SupabaseClient {
  return createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false, autoRefreshToken: false } })
}

/** A client that acts as the calling user (RLS + auth.uid() apply). */
export function userClient(req: Request): SupabaseClient {
  const auth = req.headers.get('Authorization') ?? ''
  return createClient(SUPABASE_URL, ANON_KEY, {
    global: { headers: { Authorization: auth } },
    auth: { persistSession: false, autoRefreshToken: false },
  })
}

export type Access = { user_id: string; organization_id: string; role: string; full_name: string; permissions: string[] }

/** Validates the caller's JWT with the Auth server and returns their organization access. */
export async function requireUser(req: Request): Promise<{ client: SupabaseClient; access: Access; userId: string }> {
  const auth = req.headers.get('Authorization') ?? ''
  const token = auth.replace(/^Bearer\s+/i, '')
  if (!token) throw new HttpError(401, 'Missing authorization')
  const admin = adminClient()
  const { data: u, error } = await admin.auth.getUser(token)
  if (error || !u?.user) throw new HttpError(401, 'Invalid or expired session')
  const client = userClient(req)
  const { data: access, error: e2 } = await client.rpc('my_access')
  if (e2 || !access?.organization_id) throw new HttpError(403, 'You are not a member of an organization')
  return { client, access: access as Access, userId: u.user.id }
}

export function requirePermission(access: Access, permission: string) {
  if (access.role !== 'owner' && !access.permissions.includes(permission)) {
    throw new HttpError(403, `You do not have permission to perform this action (${permission}).`)
  }
}

export function clientIp(req: Request): string {
  return (req.headers.get('x-forwarded-for') ?? '').split(',')[0].trim() || req.headers.get('cf-connecting-ip') || 'unknown'
}

export async function sha256Hex(data: Uint8Array | string): Promise<string> {
  const bytes = typeof data === 'string' ? new TextEncoder().encode(data) : data
  const hash = await crypto.subtle.digest('SHA-256', bytes as Uint8Array<ArrayBuffer>)
  return [...new Uint8Array(hash)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

export async function readJson<T = Record<string, unknown>>(req: Request): Promise<T> {
  try {
    return (await req.json()) as T
  } catch {
    throw new HttpError(400, 'Invalid JSON body')
  }
}

/** Wrap a handler with CORS + uniform error handling. */
export function serve(handler: (req: Request) => Promise<Response>) {
  Deno.serve(async (req) => {
    if (req.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders })
    try {
      return await handler(req)
    } catch (e) {
      if (e instanceof HttpError) return json({ error: e.message, code: e.code }, e.status)
      const msg = (e as { message?: string })?.message ?? String(e)
      console.error(e)
      return json({ error: msg }, 500)
    }
  })
}

/** Throw a readable error from a PostgREST/RPC error. */
export function dbError(error: { message?: string; code?: string } | null, status = 400): never {
  throw new HttpError(status, error?.message ?? 'Database error', error?.code)
}

export async function downloadBytes(admin: SupabaseClient, bucket: string, path: string): Promise<Uint8Array> {
  const { data, error } = await admin.storage.from(bucket).download(path)
  if (error || !data) throw new HttpError(500, `Could not read ${bucket}/${path}: ${error?.message ?? 'not found'}`)
  return new Uint8Array(await data.arrayBuffer())
}

/** Upload once; an existing object at the same path is treated as success (idempotent retries). */
export async function uploadOnce(admin: SupabaseClient, bucket: string, path: string, bytes: Uint8Array, contentType: string) {
  const { error } = await admin.storage.from(bucket).upload(path, bytes, { contentType, upsert: false })
  if (error && !/exists|duplicate/i.test(error.message)) {
    throw new HttpError(500, `Upload failed (${bucket}/${path}): ${error.message}`)
  }
}

export function decodeDataUrlPng(dataUrl: string): Uint8Array {
  const m = /^data:image\/png;base64,([A-Za-z0-9+/=]+)$/.exec(dataUrl ?? '')
  if (!m) throw new HttpError(400, 'Signature must be a PNG image')
  const bin = atob(m[1])
  if (bin.length > 1_500_000) throw new HttpError(413, 'Signature image is too large')
  const bytes = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
  if (!(bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47)) {
    throw new HttpError(400, 'Invalid PNG image')
  }
  if (bytes.length < 400) throw new HttpError(400, 'Signature is empty')
  return bytes
}
