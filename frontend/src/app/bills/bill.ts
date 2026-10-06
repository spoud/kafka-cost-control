import { BillEntity } from '../../generated/graphql/types';

/** The lines a bill splits by usage, in the order the page shows them. */
export const BILL_LINES = [
    { key: 'networkWrite', label: 'Network write', hint: 'produced bytes' },
    { key: 'networkRead', label: 'Network read', hint: 'consumed bytes' },
    { key: 'storage', label: 'Storage', hint: 'stored GB-hours' },
    { key: 'partitions', label: 'Partitions', hint: 'partition-hours' },
] as const;

export type BillLineKey = (typeof BILL_LINES)[number]['key'];

/** Everything on the bill, "other" included. */
export function billTotal(bill: Pick<BillEntity, BillLineKey | 'other'>): number {
    return (
        (bill.networkWrite ?? 0) +
        (bill.networkRead ?? 0) +
        (bill.storage ?? 0) +
        (bill.partitions ?? 0) +
        (bill.other ?? 0)
    );
}

/** "2026-09" -> "September 2026". Months are UTC, like the bills. */
export function monthLabel(month: string): string {
    const [year, m] = month.split('-').map(Number);
    return new Date(Date.UTC(year, m - 1, 1)).toLocaleDateString(undefined, {
        month: 'long',
        year: 'numeric',
        timeZone: 'UTC',
    });
}

/** "yyyy-MM" of the given date's UTC month. */
export function monthOf(date: Date): string {
    return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}`;
}

/** The current month and the {@code count - 1} before it, newest first. */
export function recentMonths(today: Date, count: number): string[] {
    return Array.from({ length: count }, (_, i) =>
        monthOf(new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth() - i, 1)))
    );
}

export function monthStart(month: string): Date {
    const [year, m] = month.split('-').map(Number);
    return new Date(Date.UTC(year, m - 1, 1));
}

/** The last day of the month (UTC), as a date. */
export function monthLastDay(month: string): Date {
    const [year, m] = month.split('-').map(Number);
    return new Date(Date.UTC(year, m, 0));
}

/**
 * A month-to-date bill is entered as the last day it includes; the API wants where it stops:
 * midnight UTC after that day.
 */
export function coveredUntilFromLastDay(lastDay: Date): string {
    return new Date(
        Date.UTC(lastDay.getFullYear(), lastDay.getMonth(), lastDay.getDate() + 1)
    ).toISOString();
}

/**
 * The inverse of {@link coveredUntilFromLastDay}, as a local date for the date picker. Takes the
 * API's DateTime, which the generated types leave as `unknown`.
 */
export function lastDayFromCoveredUntil(coveredUntil: unknown): Date {
    const end = new Date(String(coveredUntil));
    return new Date(end.getUTCFullYear(), end.getUTCMonth(), end.getUTCDate() - 1);
}
