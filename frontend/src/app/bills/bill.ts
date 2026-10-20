import { Allocation, BillEntity } from '../../generated/graphql/types';

/** A line as a preset or the form has it, before amounts. */
export interface LineTemplate {
    description: string;
    allocation: Allocation;
    metrics: string[];
}

/**
 * The usual lines of a provider's bill, to start a bill from: only the amounts are left to type.
 * Each METRICS line is shared by the metrics named, which is what the provider bills by.
 */
export const PRESETS: readonly { name: string; hint: string; lines: readonly LineTemplate[] }[] = [
    {
        name: 'Confluent Cloud',
        hint: "The usage lines of Confluent's invoice, shared by its metrics",
        lines: [
            metricsLine('Network write', 'confluent_kafka_server_request_bytes'),
            metricsLine('Network read', 'confluent_kafka_server_response_bytes'),
            metricsLine('Storage', 'confluent_kafka_server_retained_bytes'),
            metricsLine('Partitions', 'kafka_topic_partition_count'),
        ],
    },
    {
        name: 'Self-hosted',
        hint: 'A cluster you run yourself, e.g. with Strimzi: disks, network, and the brokers spread by usage',
        lines: [
            metricsLine('Storage', 'kafka_log_log_size'),
            metricsLine(
                'Network',
                'kafka_server_brokertopicmetrics_bytesin_total',
                'kafka_server_brokertopicmetrics_bytesout_total'
            ),
            { description: 'Brokers and operations', allocation: Allocation.Usage, metrics: [] },
        ],
    },
];

function metricsLine(description: string, ...metrics: string[]): LineTemplate {
    return { description, allocation: Allocation.Metrics, metrics };
}

/** How a line can be shared, as offered in the form. */
export const ALLOCATIONS = [
    { value: Allocation.Metrics, label: 'By metrics' },
    { value: Allocation.Context, label: 'To a context' },
    { value: Allocation.Usage, label: 'Spread by usage' },
    { value: Allocation.Shared, label: 'Shared' },
] as const;

/** One line, e.g. "Storage $8.12 → kafka_log_log_size" or "Connect $12.00 → application=etl". */
export function describeLine(line: LineLike): string {
    const amount = line.amount == null ? 'estimated' : `$${line.amount.toFixed(2)}`;
    const where =
        line.allocation === Allocation.Metrics
            ? (line.metrics ?? []).join(', ')
            : line.allocation === Allocation.Context
              ? (line.context ?? []).map(e => `${e.key}=${e.value}`).join(', ')
              : line.allocation === Allocation.Usage
                ? 'spread by usage'
                : 'shared';
    return `${line.description} ${amount} → ${where}`;
}

interface LineLike {
    description: string;
    amount?: number | null;
    allocation: Allocation;
    metrics?: readonly string[] | null;
    context?: { key?: string | null; value?: string | null }[] | null;
}

/** Everything on the bill; a line without an amount is estimated and adds nothing. */
export function billTotal(lines: readonly { amount?: number | null }[]): number {
    return lines.reduce((sum, line) => sum + (line.amount ?? 0), 0);
}

/** "September 2026", or "September 2026, poc-cluster" for a cluster's bill. */
export function billLabel(bill: Pick<BillEntity, 'month' | 'cluster'>): string {
    return bill.cluster ? `${monthLabel(bill.month)}, ${bill.cluster}` : monthLabel(bill.month);
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

/**
 * The day a bill's rates start as new prices: the UTC day its amounts end in (the 1st of the next
 * month for a whole month). The hours before are the bill's, whatever the price.
 */
export function ratesFrom(bill: Pick<BillEntity, 'month' | 'coveredUntil'>): string {
    const [year, m] = bill.month.split('-').map(Number);
    const end = bill.coveredUntil
        ? new Date(String(bill.coveredUntil))
        : new Date(Date.UTC(year, m, 1));
    return new Date(
        Date.UTC(end.getUTCFullYear(), end.getUTCMonth(), end.getUTCDate())
    ).toISOString();
}
