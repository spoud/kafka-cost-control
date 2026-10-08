import { Allocation, BillEntity } from '../../generated/graphql/types';

/**
 * The lines a bill splits by usage, in the order the page shows them, named after the metric each
 * is shared by - like the pricing rules - with what that is on the provider's bill as a hint.
 */
export const BILL_LINES = [
    {
        key: 'networkWrite',
        metric: 'confluent_kafka_server_request_bytes',
        hint: 'network write, shared by produced bytes',
    },
    {
        key: 'networkRead',
        metric: 'confluent_kafka_server_response_bytes',
        hint: 'network read, shared by consumed bytes',
    },
    {
        key: 'storage',
        metric: 'confluent_kafka_server_retained_bytes',
        hint: 'storage, shared by stored GB-hours',
    },
    {
        key: 'partitions',
        metric: 'kafka_topic_partition_count',
        hint: 'partitions, shared by partition-hours',
    },
] as const;

export type BillLineKey = (typeof BILL_LINES)[number]['key'];

/** Where an other line goes, as offered in the form. */
export const ALLOCATIONS = [
    { value: Allocation.Context, label: 'To a context' },
    { value: Allocation.Usage, label: 'Spread by usage' },
    { value: Allocation.Shared, label: 'Shared' },
] as const;

/** One line, e.g. "Connect $12.00 → application=etl". */
export function describeOtherLine(line: OtherLineLike): string {
    const where =
        line.allocation === Allocation.Context
            ? (line.context ?? []).map(e => `${e.key}=${e.value}`).join(', ')
            : line.allocation === Allocation.Usage
              ? 'spread by usage'
              : 'shared';
    return `${line.description} $${line.amount.toFixed(2)} → ${where}`;
}

interface OtherLineLike {
    description: string;
    amount: number;
    allocation: Allocation;
    context?: { key?: string | null; value?: string | null }[] | null;
}

export function otherTotal(lines: readonly { amount: number | null }[]): number {
    return lines.reduce((sum, line) => sum + (line.amount ?? 0), 0);
}

/** Everything on the bill, the other lines included. */
export function billTotal(
    bill: Pick<BillEntity, BillLineKey> & { otherLines: readonly { amount: number | null }[] }
): number {
    return (
        (bill.networkWrite ?? 0) +
        (bill.networkRead ?? 0) +
        (bill.storage ?? 0) +
        (bill.partitions ?? 0) +
        otherTotal(bill.otherLines)
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
