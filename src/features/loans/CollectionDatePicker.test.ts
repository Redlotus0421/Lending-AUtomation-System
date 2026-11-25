import { expect, it } from 'vitest'
import { collectionDates, defaultFirstDue } from './CollectionDatePicker'
it('offers collection dates that match the server schedule rules', () => {
  expect(collectionDates('monthly', 10, '2026-11-25')).toEqual(['2026-12-10', '2027-01-10', '2027-02-10'])
  expect(collectionDates('monthly', 31, '2026-11-25')).toEqual(['2026-12-31', '2027-01-31', '2027-03-31'])
  expect(collectionDates('monthly', 28, '2026-11-25')).toEqual(['2026-12-28', '2027-01-28', '2027-02-28'])
  expect(collectionDates('weekly', 5, '2026-11-25')).toEqual(['2026-11-27', '2026-12-04', '2026-12-11']) // Fri
  expect(collectionDates('weekly', 3, '2026-11-25')).toEqual(['2026-12-02', '2026-12-09', '2026-12-16']) // Wed: not today
  expect(collectionDates('weekly', 10, '2026-11-25')).toEqual([]) // stale monthly day: no loop
  expect(collectionDates('monthly', 0, '2026-11-25')).toEqual([])
  expect(collectionDates('daily', 0, '2026-11-25')).toEqual(['2026-11-26', '2026-11-27', '2026-11-28', '2026-11-29'])
  expect(defaultFirstDue('monthly', '2026-01-31')).toBe('2026-02-28')
})
