// "New loan" entry point shared by the header, the mobile bottom bar and list pages. In Simplified
// Lending it opens a popup to pick New loan or Reloan / Renew before the wizard opens; in Complete
// Lending it goes straight to the loan wizard.
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useAuth } from '@/features/auth/AuthProvider'
import type { TransactionType } from '@/services/simplified'
import { TransactionOptions } from './simplified/TransactionOptions'

type Ctx = {
  /** Start a new loan: popup in Simplified Lending, the wizard otherwise. */
  open: () => void
  /** Bumped on every pick so /loans/new remounts with fresh state even when it is already open. */
  nonce: number
}
const NewLoanContext = createContext<Ctx | null>(null)

export function NewLoanChooserProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const { organization, can } = useAuth()
  const [show, setShow] = useState(false)
  const [nonce, setNonce] = useState(0)
  const simplified = organization?.lending_mode === 'simplified'

  const open = useCallback(() => {
    if (simplified) setShow(true)
    else navigate('/loans/new')
  }, [simplified, navigate])

  const pick = (t: TransactionType) => {
    setShow(false)
    setNonce((n) => n + 1)
    navigate(`/loans/new?type=${t}`)
  }

  const canRegister = can('borrowers.create') && can('borrowers.view_sensitive')
  const value = useMemo(() => ({ open, nonce }), [open, nonce])

  return (
    <NewLoanContext.Provider value={value}>
      {children}
      <Dialog open={show} onOpenChange={setShow}>
        <DialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Create a loan</DialogTitle>
            <DialogDescription>Choose the type of transaction.</DialogDescription>
          </DialogHeader>
          <TransactionOptions compact onPick={pick}
            disabled={canRegister ? undefined : { new: 'Needs permission to register borrowers and handle identity documents.' }} />
        </DialogContent>
      </Dialog>
    </NewLoanContext.Provider>
  )
}

export function useNewLoan(): Ctx {
  const ctx = useContext(NewLoanContext)
  const navigate = useNavigate()
  return ctx ?? { open: () => navigate('/loans/new'), nonce: 0 }
}
