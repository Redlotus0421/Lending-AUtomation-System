// Dashboard charts (Recharts). Palette validated with the dataviz validator
// (light surface): blue #2563EB, teal #0d9488, yellow #eda100, violet #6250d6.
import type { ReactNode } from 'react'
import {
  Area, AreaChart, Bar, BarChart, CartesianGrid, Cell, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import { peso } from '@/lib/format'

export const SERIES = { blue: '#2563EB', teal: '#0d9488', yellow: '#eda100', violet: '#6250d6' }
// Ordinal blue ramp for ageing buckets (current -> 90+), lightest step still >= 2:1 on white
const AGING_RAMP = ['#86b6ef', '#5598e7', '#2a78d6', '#1c5cab', '#104281']

const axisTick = { fontSize: 11, fill: '#5b6b82' }
const gridStroke = '#eef1f6'

function compactPeso(v: number) {
  return peso(v, { compact: true })
}

type TipPayload = { name?: string; value?: number | string; color?: string; dataKey?: string | number; payload?: Record<string, unknown> }

function ChartTooltip({ active, payload, label, extra }: { active?: boolean; payload?: TipPayload[]; label?: ReactNode; extra?: (p: Record<string, unknown>) => ReactNode }) {
  if (!active || !payload?.length) return null
  const total = payload.length > 1 ? payload.reduce((a, p) => a + Number(p.value ?? 0), 0) : null
  return (
    <div className="min-w-44 rounded-lg border bg-white px-3 py-2 text-xs shadow-lg">
      <p className="mb-1.5 font-semibold text-foreground">{label}</p>
      {payload.map((p) => (
        <div key={String(p.dataKey)} className="flex items-center justify-between gap-4 py-0.5">
          <span className="flex items-center gap-1.5 text-muted-foreground">
            <span className="size-2.5 rounded-sm" style={{ background: p.color }} />
            {p.name}
          </span>
          <span className="tabular font-medium text-foreground">{peso(p.value)}</span>
        </div>
      ))}
      {total !== null && (
        <div className="mt-1 flex justify-between gap-4 border-t pt-1 font-semibold">
          <span>Total</span>
          <span className="tabular">{peso(total)}</span>
        </div>
      )}
      {extra && payload[0]?.payload && <div className="mt-1 border-t pt-1 text-muted-foreground">{extra(payload[0].payload)}</div>}
    </div>
  )
}

export type MonthPoint = {
  month: string
  label: string
  released: number
  loans_released: number
  collected: number
  principal_collected: number
  interest_collected: number
  other_collected: number
  scheduled_due: number
  paid_on_due: number
}

function ChartFrame({ children, height = 240 }: { children: ReactNode; height?: number }) {
  return <div style={{ height }} className="w-full">{children}</div>
}

export function ReleasesChart({ data }: { data: MonthPoint[] }) {
  return (
    <ChartFrame>
      <ResponsiveContainer>
        <BarChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
          <CartesianGrid vertical={false} stroke={gridStroke} />
          <XAxis dataKey="label" tick={axisTick} tickLine={false} axisLine={false} interval="preserveStartEnd" />
          <YAxis tick={axisTick} tickLine={false} axisLine={false} tickFormatter={compactPeso} width={64} />
          <Tooltip cursor={{ fill: 'rgba(37,99,235,0.06)' }}
            content={<ChartTooltip extra={(p) => `${p.loans_released} loan${p.loans_released === 1 ? '' : 's'} released`} />} />
          <Bar dataKey="released" name="Released" fill={SERIES.blue} radius={[4, 4, 0, 0]} maxBarSize={28} />
        </BarChart>
      </ResponsiveContainer>
    </ChartFrame>
  )
}

export function CollectionsChart({ data }: { data: MonthPoint[] }) {
  return (
    <ChartFrame>
      <ResponsiveContainer>
        <AreaChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
          <defs>
            <linearGradient id="collFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={SERIES.teal} stopOpacity={0.28} />
              <stop offset="100%" stopColor={SERIES.teal} stopOpacity={0.02} />
            </linearGradient>
          </defs>
          <CartesianGrid vertical={false} stroke={gridStroke} />
          <XAxis dataKey="label" tick={axisTick} tickLine={false} axisLine={false} interval="preserveStartEnd" />
          <YAxis tick={axisTick} tickLine={false} axisLine={false} tickFormatter={compactPeso} width={64} />
          <Tooltip cursor={{ stroke: '#94a3b8', strokeDasharray: '3 3' }} content={<ChartTooltip />} />
          <Area type="monotone" dataKey="collected" name="Collected" stroke={SERIES.teal} strokeWidth={2} fill="url(#collFill)"
            dot={false} activeDot={{ r: 5, strokeWidth: 2, stroke: '#fff' }} />
        </AreaChart>
      </ResponsiveContainer>
    </ChartFrame>
  )
}

export function ComponentsChart({ data }: { data: MonthPoint[] }) {
  return (
    <ChartFrame height={260}>
      <ResponsiveContainer>
        <BarChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }}>
          <CartesianGrid vertical={false} stroke={gridStroke} />
          <XAxis dataKey="label" tick={axisTick} tickLine={false} axisLine={false} interval="preserveStartEnd" />
          <YAxis tick={axisTick} tickLine={false} axisLine={false} tickFormatter={compactPeso} width={64} />
          <Tooltip cursor={{ fill: 'rgba(37,99,235,0.06)' }} content={<ChartTooltip />} />
          <Legend iconType="square" iconSize={10} wrapperStyle={{ fontSize: 12, color: '#5b6b82' }} />
          <Bar dataKey="principal_collected" name="Principal" stackId="c" fill={SERIES.blue} stroke="#fff" strokeWidth={1} maxBarSize={28} />
          <Bar dataKey="interest_collected" name="Interest" stackId="c" fill={SERIES.teal} stroke="#fff" strokeWidth={1} maxBarSize={28} />
          <Bar dataKey="other_collected" name="Charges & penalties" stackId="c" fill={SERIES.yellow} stroke="#fff" strokeWidth={1} radius={[4, 4, 0, 0]} maxBarSize={28} />
        </BarChart>
      </ResponsiveContainer>
    </ChartFrame>
  )
}

export function PerformanceChart({ data }: { data: MonthPoint[] }) {
  return (
    <ChartFrame height={260}>
      <ResponsiveContainer>
        <BarChart data={data} margin={{ top: 8, right: 8, left: 0, bottom: 0 }} barGap={2}>
          <CartesianGrid vertical={false} stroke={gridStroke} />
          <XAxis dataKey="label" tick={axisTick} tickLine={false} axisLine={false} interval="preserveStartEnd" />
          <YAxis tick={axisTick} tickLine={false} axisLine={false} tickFormatter={compactPeso} width={64} />
          <Tooltip cursor={{ fill: 'rgba(37,99,235,0.06)' }}
            content={<ChartTooltip extra={(p) => {
              const due = Number(p.scheduled_due ?? 0)
              const paid = Number(p.paid_on_due ?? 0)
              return due > 0 ? `Collection rate: ${((paid / due) * 100).toFixed(1)}%` : 'Nothing scheduled'
            }} />} />
          <Legend iconType="square" iconSize={10} wrapperStyle={{ fontSize: 12, color: '#5b6b82' }} />
          <Bar dataKey="scheduled_due" name="Scheduled due" fill={SERIES.violet} radius={[4, 4, 0, 0]} maxBarSize={18} />
          <Bar dataKey="paid_on_due" name="Paid toward it" fill={SERIES.teal} radius={[4, 4, 0, 0]} maxBarSize={18} />
        </BarChart>
      </ResponsiveContainer>
    </ChartFrame>
  )
}

export type AgingPoint = { bucket: string; loans: number; outstanding_principal: number }

const BUCKET_ORDER = ['Current', '1–30 days', '31–60 days', '61–90 days', '90+ days']

export function AgingChart({ data }: { data: AgingPoint[] }) {
  const full = BUCKET_ORDER.map((b) => data.find((d) => d.bucket === b) ?? { bucket: b, loans: 0, outstanding_principal: 0 })
  return (
    <ChartFrame height={230}>
      <ResponsiveContainer>
        <BarChart data={full} layout="vertical" margin={{ top: 4, right: 16, left: 4, bottom: 0 }}>
          <CartesianGrid horizontal={false} stroke={gridStroke} />
          <XAxis type="number" tick={axisTick} tickLine={false} axisLine={false} tickFormatter={compactPeso} />
          <YAxis type="category" dataKey="bucket" tick={axisTick} tickLine={false} axisLine={false} width={80} />
          <Tooltip cursor={{ fill: 'rgba(37,99,235,0.06)' }}
            content={<ChartTooltip extra={(p) => `${p.loans} loan${p.loans === 1 ? '' : 's'}`} />} />
          <Bar dataKey="outstanding_principal" name="Outstanding principal" radius={[0, 4, 4, 0]} maxBarSize={22}>
            {full.map((d, i) => <Cell key={d.bucket} fill={AGING_RAMP[i]} />)}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </ChartFrame>
  )
}
