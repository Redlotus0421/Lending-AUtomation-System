// Thin, reusable data-access helpers. All business rules live in the database
// (RPCs, RLS, triggers) and Edge Functions so that future clients (e.g. Android)
// share exactly the same behaviour.
import { supabase } from '@/lib/supabase'
import { edgeErrorMessage, errorMessage } from '@/lib/errors'

export class ApiError extends Error {
  code?: string
  constructor(message: string, code?: string) {
    super(message)
    this.code = code
  }
}

export async function rpc<T>(fn: string, args?: Record<string, unknown>): Promise<T> {
  const { data, error } = await supabase.rpc(fn, args ?? {})
  if (error) throw new ApiError(errorMessage(error), error.code)
  return data as T
}

export async function invoke<T>(fn: string, body: Record<string, unknown>): Promise<T> {
  const { data, error } = await supabase.functions.invoke(fn, { body })
  if (error) throw new ApiError(await edgeErrorMessage(error))
  if (data && typeof data === 'object' && 'error' in data && (data as { error?: string }).error) {
    throw new ApiError(String((data as { error: string }).error))
  }
  return data as T
}

export function must<T>(res: { data: T | null; error: { message: string; code?: string } | null }): T {
  if (res.error) throw new ApiError(errorMessage(res.error), res.error.code)
  return res.data as T
}

export async function signedUrl(bucket: string, path: string, seconds = 600, download?: string): Promise<string> {
  const { data, error } = await supabase.storage.from(bucket).createSignedUrl(path, seconds, download ? { download } : undefined)
  if (error || !data) throw new ApiError(errorMessage(error, 'Could not open the file'))
  return data.signedUrl
}

export async function uploadFile(bucket: string, path: string, file: File | Blob, upsert = false) {
  const { error } = await supabase.storage.from(bucket).upload(path, file, {
    upsert,
    contentType: (file as File).type || undefined,
    cacheControl: '3600',
  })
  if (error) throw new ApiError(errorMessage(error, 'Upload failed'))
  return path
}

export function safeFileName(name: string) {
  const ext = name.includes('.') ? name.split('.').pop()!.toLowerCase().replace(/[^a-z0-9]/g, '') : 'bin'
  const base = name.replace(/\.[^.]+$/, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 40) || 'file'
  return `${base}-${Date.now().toString(36)}.${ext}`
}

export function newIdempotencyKey() {
  return crypto.randomUUID()
}
