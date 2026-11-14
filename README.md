# LendFlow — Lending Management System (Prototype V1)

A lending operations platform for Philippine lenders: borrowers → loan creation with server-side
calculations → configurable agreement templates → server-generated PDF agreements → mobile
e-signing with signatures permanently embedded in the PDF → approval → release → collections,
receipts, an immutable ledger, reports and audit logs.

**Stack:** React 19 + TypeScript + Vite + Tailwind v4 + shadcn/ui · TanStack Query · React Hook Form + Zod ·
Recharts · Supabase (Postgres, Auth, Storage, Edge Functions) · pdf-lib (server) · pdf.js (viewer) · signature_pad.

---

## 1. Run locally

```bash
npm install
cp .env.example .env      # set VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY (public values only)
npm run dev               # http://localhost:5173
```

Other scripts: `npm run build`, `npm run typecheck`, `npm run test:db` (database tests in PGlite), `npm run db:bundle`.

## 2. Deploy the backend to Supabase

The backend is everything under `supabase/`. You need the Supabase CLI logged into the account that owns the project.

```bash
npx supabase login
npx supabase link --project-ref <your-project-ref>      # asks for the database password
npx supabase db push                                    # applies supabase/migrations/*.sql
npx supabase functions deploy                           # deploys all 5 Edge Functions
```

No function secrets need to be set: `SUPABASE_URL`, `SUPABASE_ANON_KEY` and `SUPABASE_SERVICE_ROLE_KEY` are
injected automatically. Never put the service-role key in `.env`.

**Without the CLI (database only):** paste `supabase/deploy/lendflow_all_migrations.sql` into the SQL Editor and run it
once. Edge Functions still require the CLI.

**Auth settings (Dashboard → Authentication):**
- *Sign In / Providers → Allow new users to sign up*: **off**. Lender accounts are created only by the platform admin;
  logins are created server-side with the admin API, which works with sign-ups disabled.
- *URL Configuration*: set *Site URL* to your app URL and add it to the redirect URLs (needed for password reset emails).

**First run:**
1. Create the platform super admin: `npm run seed:admin -- you@example.com` (needs the CLI login/link above). The
   password is typed at a hidden prompt and is never written to a file.
2. Sign in → the admin console (`/admin`) → **Create account** for each lending business (business details + owner
   email and temporary password). This seeds its loan products, agreement template and role permissions.
3. The owner signs in, sets their own password, optionally loads sample data in *Settings → Sample data*, and adds
   staff in *Users & Staff*.

### Platform administration
The super admin creates, edits, suspends and reactivates lender accounts, adds logins and resets passwords. They are
not members of any account, so RLS gives them no access to borrower, loan, payment or document records — the console
shows only per-account counts and totals. Suspending an account blocks all of its users (no records are changed).
Self-service sign-up and onboarding are disabled (`create_organization` refuses), so a stray login gets no access.

## 2b. Deploy the web app (Vercel)

Import the GitHub repository in Vercel (framework preset **Vite**; `vercel.json` sets the build and the SPA rewrite so
links such as `/sign/<token>` and `/admin` open directly). Then:

1. *Project → Settings → Environment Variables*: add `VITE_SUPABASE_URL` and `VITE_SUPABASE_ANON_KEY` (the same public
   values as `.env`) for Production and Preview. Vite bakes them in at build time, so **redeploy** after adding them.
2. Supabase *Authentication → URL Configuration*: set *Site URL* to the Vercel URL and add `https://<your-app>.vercel.app/**`
   (and `http://localhost:5173/**` for local work) to the redirect URLs, so password-reset links return to the app.

Never add the service-role key to Vercel: the browser only needs the anon key; privileged work runs in Edge Functions.

## 3. Architecture

```
src/
  services/          API layer (pure async functions; no React) — reusable by any client
  features/<module>/ pages and dialogs per module
  components/        shadcn/ui + shared components (DataTable, PdfViewer, SignaturePad, …)
  lib/               formatting (₱, Asia/Manila), constants (PH IDs, regions), errors, export
supabase/
  migrations/        schema, RLS, financial engine and workflow RPCs (the business rules live here)
  functions/         Edge Functions (Deno): PDFs, signing, receipts, staff admin
    _shared/         template engine + formatters shared with the web app, PDF layout engine
tests/db/            PGlite-based tests that run every migration and the acceptance scenarios
```

Business logic is **not** in React components:

| Concern | Where |
|---|---|
| Loan calculation (flat / diminishing, frequencies, day count, rounding, EIR) | `compute_loan_schedule()` (PL/pgSQL, `numeric` = decimal-safe) |
| Loan lifecycle (create, submit, approve, reject, cancel, release, default, close, reschedule) | `*_loan` RPCs with row locks |
| Payments, allocation, penalties, settlement, reversal, adjustments | `record_payment`, `reverse_payment`, `apply_loan_adjustment`, `get_settlement_quote` |
| Ledger & balances | `lf_post_ledger()` — the only place outstanding balances change |
| Agreement generation & signing state | `agreement_begin` / `agreement_complete` / `signature_commit` / `agreement_set_pdf` |
| PDF rendering, signature embedding, SHA-256 | Edge Functions `agreement-generate`, `agreement-sign`, `public-signing` |
| Receipts PDF | Edge Function `receipt-pdf` (from a frozen receipt snapshot) |
| Permissions & org isolation | `has_permission()`, `can_access_loan/borrower()`, RLS on every table, storage policies |

### Key rules implemented
- **Rates:** entered rate = nominal rate per chosen period; periodic rate = rate × ppy(rate period) ÷ ppy(frequency)
  (monthly 12, weekly 52, daily 360 on 30/360 or 365 on actual). Conversions are shown in the preview disclosures.
  Diminishing loans can accrue on actual days (actual/365, actual/actual — leap years, irregular first period).
  Final installment absorbs rounding. Effective interest rate (IRR) is disclosed.
- **Separate balances:** principal, interest, charges, penalties, total. Allocation order is configurable
  (default penalty → charges → interest → principal), installment by installment.
- **Early settlement** charges interest earned to date only (prorated / full current period / due-only, configurable);
  unearned interest is rebated and ledgered.
- **No deletes** on financial or document records (triggers). Corrections are reversals (latest payment first) or
  adjustments, each with a ledger entry. Posted payments, receipts, snapshots, signatures and signed agreements are immutable.
- **Duplicate protection:** idempotency key per payment dialog + loan row lock + unique bank/e-wallet references.
- **Signing:** time-limited single-use links (hashed tokens), identity check (date of birth or ID last 4), rate limits,
  lockout after 5 failures, consent record, IP/device capture. Each signature is committed in a DB transaction, then the
  signed PDF is rebuilt deterministically from the frozen unsigned PDF + all committed signatures and stored with its
  SHA-256 (compare-and-set). Retries are idempotent; a committed signature survives closing the browser or losing the
  connection, and a "Rebuild signed PDF" action recovers an interrupted finalization. The final PDF includes an
  Electronic Signature Certificate page.
- **Amendments:** a signed agreement can never be overwritten; rescheduling creates an amendment snapshot and a new
  agreement version with its own signing process.

> A visually embedded signature is not a certificate-based digital signature. The design (frozen unsigned PDF, hashes,
> signing records) allows adding PAdES/certificate signing later.

## 4. Acceptance tests

| # | Scenario | Covered by |
|---|---|---|
| 1 | ₱50,000, 2%/month flat, 10 months → ₱10,000 interest, ₱60,000 total, ₱6,000/month | `tests/db/engine.test.ts` |
| 2 | Same loan, diminishing balance → interest decreases | `tests/db/engine.test.ts` |
| 3 | Agreement populated with borrower, terms, dates, schedule | `agreement_begin` render data test + PDF renderer |
| 4–6 | Mobile signing, lender signing, signatures embedded and persistent | signing workflow test (DB) + Edge Functions — verify end-to-end after deploy |
| 7 | Full installment payment → installment, ledger, receipt, balances | `tests/db/workflow.test.ts` |
| 8 | Partial payment remainder (with late penalty) | `tests/db/workflow.test.ts` |
| 9 | Duplicate payment prevented | `tests/db/workflow.test.ts` |
| 10 | Signed agreement cannot be modified | `tests/db/workflow.test.ts` |
| 11 | Collector cannot access settings / unassigned borrowers | `tests/db/workflow.test.ts` |
| 12 | Organization isolation | `tests/db/workflow.test.ts` |
| — | Super admin console, account suspension, no self sign-up | `tests/db/platform.test.ts` |

Run them with `npm run test:db`.

## 5. Future Android app

All operations are Supabase RPCs / Edge Functions with JWT auth and RLS, so a native client reuses them unchanged:
`create_loan`, `preview_loan`, `record_payment`, `get_settlement_quote`, `signing_session_create`,
`agreement-generate`, `agreement-sign`, `public-signing`, `receipt-pdf`, storage buckets for photos/IDs, etc.

## 6. Compliance notice

Templates and calculations are configurable tools, not legal advice. Before production use, have the system and your
agreement templates reviewed for applicable Philippine requirements (SEC lending company rules and disclosure
requirements, Truth in Lending Act — R.A. 3765, Data Privacy Act — R.A. 10173, E-Commerce Act — R.A. 8792, fair debt
collection rules). A signed agreement is not automatically compliant.
