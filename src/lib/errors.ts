/** Turn Supabase/PostgREST/Edge Function errors into a readable sentence. */
export function errorMessage(e: unknown, fallback = 'Something went wrong. Please try again.'): string {
  if (!e) return fallback
  if (typeof e === 'string') return e
  const err = e as { message?: string; details?: string; hint?: string; code?: string; context?: unknown }
  let msg = err.message ?? ''
  if (/Failed to fetch|NetworkError|Load failed/i.test(msg)) return 'Network error — check your internet connection and try again.'
  if (err.code === '23505' || /duplicate key/i.test(msg)) {
    if (/borrowers_dup_name_dob/.test(msg)) return 'A borrower with the same name and date of birth already exists.'
    if (/borrower_identities_doc/.test(msg)) return 'Another borrower is already registered with this ID document.'
    if (/loan_payments_reference/.test(msg)) return 'A payment with this transaction reference already exists.'
    if (/loan_products_organization_id_code/.test(msg)) return 'A product with this code already exists.'
    return msg.includes('duplicate key') ? 'This record already exists.' : msg
  }
  if (err.code === '42501' || /row-level security/i.test(msg)) {
    return /row-level security/i.test(msg) ? 'You do not have permission to perform this action.' : msg
  }
  if (err.code === 'PGRST301' || /JWT expired/i.test(msg)) return 'Your session expired. Please sign in again.'
  msg = msg.replace(/^Edge Function returned a non-2xx status code$/, '')
  return msg || err.details || fallback
}

/** Edge Function errors carry the JSON body in error.context (a Response). */
export async function edgeErrorMessage(error: unknown): Promise<string> {
  const ctx = (error as { context?: Response })?.context
  if (ctx && typeof ctx.json === 'function') {
    try {
      const body = await ctx.clone().json()
      if (body?.error) return String(body.error)
    } catch {
      /* ignore */
    }
  }
  return errorMessage(error)
}
