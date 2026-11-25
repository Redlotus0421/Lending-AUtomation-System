// Real-time sync: when any signed-in device (this browser, another browser or the Android app)
// changes a record, Supabase Realtime notifies every client and the visible queries re-load.
// Row-level security applies to Realtime as well, so users only hear about rows they may read.
// Needs migration 20261126000022_realtime_sync.sql (adds the tables to `supabase_realtime`).
import type { QueryClient } from '@tanstack/react-query'
import { supabase } from '@/lib/supabase'

export const REALTIME_TABLES = [
  'borrowers', 'borrower_documents', 'loans', 'loan_installments', 'loan_payments', 'payment_receipts',
  'loan_agreements', 'agreement_signatures', 'agreement_signing_sessions', 'loan_notes', 'notifications',
  'loan_products', 'loan_agreement_templates', 'user_profiles', 'user_roles', 'organizations', 'loan_renewals',
] as const

/** Subscribes to database changes and invalidates React Query caches (debounced). Returns an unsubscribe function. */
export function startRealtimeSync(qc: QueryClient): () => void {
  let timer: ReturnType<typeof setTimeout> | undefined
  const refresh = () => {
    clearTimeout(timer)
    // one payment touches several tables: refetch once per burst
    timer = setTimeout(() => void qc.invalidateQueries(), 400)
  }
  let channel = supabase.channel('lendflow-web-sync')
  for (const table of REALTIME_TABLES) {
    channel = channel.on('postgres_changes', { event: '*', schema: 'public', table }, refresh)
  }
  channel.subscribe()
  return () => {
    clearTimeout(timer)
    void supabase.removeChannel(channel)
  }
}
