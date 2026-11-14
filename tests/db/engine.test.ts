import { beforeAll, describe, expect, it } from 'vitest'
import { createDb, one, type Db } from './harness'

let db: Db
beforeAll(async () => {
  db = await createDb()
})

type Row = {
  installment_no: number
  due_date: string
  opening_balance: number
  principal: number
  interest: number
  charges: number
  total: number
  closing_balance: number
}
type Calc = { summary: Record<string, any>; schedule: Row[]; warnings: string[]; disclosures: string[] }

async function calc(p: Record<string, unknown>) {
  const r = await one<{ r: Calc }>(db, `select public.compute_loan_schedule($1::jsonb) as r`, [JSON.stringify(p)])
  return r.r
}

const base = { principal: 50000, interest_rate: 2, rate_period: 'monthly', repayment_frequency: 'monthly', term_count: 10, release_date: '2026-01-15' }

describe('loan engine', () => {
  it('TEST 1: flat 2% monthly, 10 months, PHP 50,000', async () => {
    const r = await calc({ ...base, interest_method: 'flat' })
    expect(Number(r.summary.total_interest)).toBe(10000)
    expect(Number(r.summary.total_repayment)).toBe(60000)
    expect(Number(r.summary.installment_amount)).toBe(6000)
    expect(r.schedule).toHaveLength(10)
    for (const row of r.schedule) {
      expect(Number(row.total)).toBe(6000)
      expect(Number(row.interest)).toBe(1000)
      expect(Number(row.principal)).toBe(5000)
    }
    expect(r.schedule[0].due_date).toBe('2026-02-15')
    expect(r.schedule[9].due_date).toBe('2026-11-15')
    expect(Number(r.schedule[9].closing_balance)).toBe(0)
  })

  it('TEST 2: diminishing balance — interest decreases as principal is paid', async () => {
    const r = await calc({ ...base, interest_method: 'diminishing' })
    const s = r.schedule
    // PMT = 50000 * 0.02 / (1 - 1.02^-10) = 5566.33
    expect(Number(s[0].total)).toBeCloseTo(5566.33, 2)
    expect(Number(s[0].interest)).toBe(1000)
    for (let i = 1; i < s.length; i++) {
      expect(Number(s[i].interest)).toBeLessThan(Number(s[i - 1].interest))
      expect(Number(s[i].opening_balance)).toBe(Number(s[i - 1].closing_balance))
    }
    const totalPrincipal = s.reduce((a, x) => a + Number(x.principal), 0)
    expect(Math.round(totalPrincipal * 100) / 100).toBe(50000)
    expect(Number(s[9].closing_balance)).toBe(0)
    expect(Number(r.summary.total_interest)).toBeLessThan(10000)
    expect(Number(r.summary.total_interest)).toBeCloseTo(5663.3, 0)
  })

  it('anchors month-end due dates and handles leap years', async () => {
    const r = await calc({ principal: 12000, interest_method: 'flat', interest_rate: 1, rate_period: 'monthly', repayment_frequency: 'monthly', term_count: 4, release_date: '2027-12-31', first_due_date: '2028-01-31' })
    expect(r.schedule.map((x) => x.due_date)).toEqual(['2028-01-31', '2028-02-29', '2028-03-31', '2028-04-30'])
  })

  it('actual/actual diminishing amortizes exactly to zero', async () => {
    const r = await calc({ principal: 100000, interest_method: 'diminishing', interest_rate: 24, rate_period: 'yearly', repayment_frequency: 'monthly', term_count: 12, release_date: '2027-11-10', first_due_date: '2027-12-10', day_count: 'actual/actual' })
    const s = r.schedule
    expect(Number(s[11].closing_balance)).toBe(0)
    const totals = s.slice(0, 11).map((x) => Number(x.total))
    expect(new Set(totals).size).toBe(1)
    expect(Math.abs(Number(s[11].total) - totals[0])).toBeLessThan(1)
  })

  it('converts a monthly rate explicitly for weekly payments', async () => {
    const r = await calc({ principal: 10000, interest_method: 'flat', interest_rate: 2, rate_period: 'monthly', repayment_frequency: 'weekly', term_count: 12, release_date: '2026-03-02' })
    expect(Number(r.summary.periodic_rate_pct)).toBeCloseTo(0.461538, 5)
    expect(Number(r.summary.total_interest)).toBeCloseTo(553.85, 2)
    expect(r.schedule[1].due_date).toBe('2026-03-16')
  })

  it('daily 30/360: 60 days at 2%/month = 4%', async () => {
    const r = await calc({ principal: 10000, interest_method: 'flat', interest_rate: 2, rate_period: 'monthly', repayment_frequency: 'daily', term_count: 60, release_date: '2026-03-02' })
    expect(Number(r.summary.total_interest)).toBe(400)
    const sum = r.schedule.reduce((a, x) => a + Number(x.total), 0)
    expect(Math.round(sum * 100) / 100).toBe(10400)
  })

  it('handles fees added vs deducted and computes the EIR', async () => {
    const added = await calc({ ...base, interest_method: 'flat', processing_fee_type: 'percent', processing_fee_value: 3, charges_treatment: 'added' })
    expect(Number(added.summary.processing_fee)).toBe(1500)
    expect(Number(added.summary.total_repayment)).toBe(61500)
    expect(Number(added.summary.installment_amount)).toBe(6150)
    expect(Number(added.summary.net_proceeds)).toBe(50000)
    const deducted = await calc({ ...base, interest_method: 'flat', processing_fee_type: 'fixed', processing_fee_value: 1000, charges_treatment: 'deducted' })
    expect(Number(deducted.summary.net_proceeds)).toBe(49000)
    expect(Number(deducted.summary.total_repayment)).toBe(60000)
    expect(Number(deducted.summary.eir_monthly_pct)).toBeGreaterThan(3.5)
  })

  it('rejects invalid inputs', async () => {
    await expect(calc({ principal: 0, term_count: 10 })).rejects.toThrow(/greater than zero/)
    await expect(calc({ principal: 1000, term_count: 0 })).rejects.toThrow(/at least 1/)
    await expect(calc({ principal: 1000, term_count: 3, release_date: '2026-01-10', first_due_date: '2026-01-05' })).rejects.toThrow(/after the loan release/)
  })
})
