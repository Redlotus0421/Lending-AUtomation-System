// Shared helpers for the loan-creation wizards.
//
// * The current step lives in the URL (?step=…), and moving forward pushes a history entry, so the
//   browser's Back / Forward buttons (and the phone's back gesture) move between steps instead of
//   leaving the page. The wizard component stays mounted, so nothing typed is lost.
// * Entries are also saved to sessionStorage (this browser tab only, cleared on sign-out), so leaving
//   the page by accident, following a link or reloading restores them.
// * Picked files cannot be stored in sessionStorage; they are kept in memory for the session of the
//   single-page app and survive in-app navigation (not a full reload).
import { useCallback, useEffect, useRef } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'

export const DRAFT_PREFIX = 'lendflow:loan-draft:'

type WizardState = { wizardFrom?: string }

export function useWizardSteps<T extends string>(fallback: T, key = 'step') {
  const [params, setParams] = useSearchParams()
  const location = useLocation()
  const navigate = useNavigate()
  const step = (params.get(key) ?? fallback) as T

  /** Move to a step. Pushes a history entry unless `replace` is set. `extra` sets/removes other URL params. */
  const go = useCallback((next: T, opts: { replace?: boolean; extra?: Record<string, string | null> } = {}) => {
    const p = new URLSearchParams(params)
    p.set(key, next)
    for (const [k, v] of Object.entries(opts.extra ?? {})) {
      if (v === null) p.delete(k)
      else p.set(k, v)
    }
    setParams(p, { replace: opts.replace, state: { wizardFrom: step } satisfies WizardState })
  }, [params, setParams, key, step])

  /**
   * Go back to `prev`. If this history entry was reached from `prev`, behave exactly like the browser
   * Back button (so Forward still works); otherwise replace the entry so history does not pile up.
   */
  const back = useCallback((prev: T) => {
    if ((location.state as WizardState | null)?.wizardFrom === prev) navigate(-1)
    else go(prev, { replace: true })
  }, [location.state, navigate, go])

  // every step change (buttons or browser history) starts at the top of the page
  const first = useRef(true)
  useEffect(() => {
    if (first.current) { first.current = false; return }
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }, [step])

  return { step, go, back, params }
}

export function loadDraft<T>(key: string): T | null {
  try {
    const raw = sessionStorage.getItem(DRAFT_PREFIX + key)
    return raw ? (JSON.parse(raw) as T) : null
  } catch {
    return null
  }
}

export function saveDraft(key: string, value: unknown) {
  try {
    sessionStorage.setItem(DRAFT_PREFIX + key, JSON.stringify(value))
  } catch {
    // storage full or blocked: the in-page state still works
  }
}

export function clearDraft(key: string) {
  try {
    sessionStorage.removeItem(DRAFT_PREFIX + key)
  } catch {
    // ignore
  }
  memoryFiles.delete(key)
}

/** Remove every saved loan draft (called on sign-out: drafts contain borrower details). */
export function clearAllDrafts() {
  try {
    for (let i = sessionStorage.length - 1; i >= 0; i--) {
      const k = sessionStorage.key(i)
      if (k?.startsWith(DRAFT_PREFIX)) sessionStorage.removeItem(k)
    }
  } catch {
    // ignore
  }
  memoryFiles.clear()
}

const memoryFiles = new Map<string, unknown>()
/** Keep non-serializable values (File objects) for the lifetime of the app session. */
export const draftFiles = {
  get: <T,>(key: string) => memoryFiles.get(key) as T | undefined,
  set: (key: string, value: unknown) => { memoryFiles.set(key, value) },
}

/** Call `save` with the latest value, debounced, whenever `value` changes. */
export function useAutosave(enabled: boolean, value: unknown, save: () => void, delay = 300) {
  const json = JSON.stringify(value)
  const saveRef = useRef(save)
  saveRef.current = save
  useEffect(() => {
    if (!enabled) return
    const t = setTimeout(() => saveRef.current(), delay)
    return () => clearTimeout(t)
  }, [enabled, json, delay])
}
