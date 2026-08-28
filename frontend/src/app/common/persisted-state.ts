/**
 * Helpers for state we persist to localStorage.
 *
 * Two things have bitten us here before, both of which take down a whole route rather than
 * degrading, because the stores that read this are `providedIn: 'root'` and hydrate during
 * construction — there is no in-app way back, the user has to clear localStorage by hand.
 *
 * 1. Unparseable JSON. Rare, but a half-written value or a hand-edited one throws.
 * 2. A value that parses fine but predates a shape change. A field added since it was written
 *    reads back as `undefined`, and the first template that treats it as an array or a Date
 *    throws.
 *
 * So every read goes through a type guard, and every write is wrapped in a version envelope:
 * bumping the version on a breaking shape change discards old values in one line, instead of
 * growing another retroactive per-field check.
 */

interface Envelope {
    v: number;
    data: unknown;
}

function isEnvelope(value: unknown): value is Envelope {
    return (
        !!value &&
        typeof value === 'object' &&
        typeof (value as Envelope).v === 'number' &&
        'data' in value
    );
}

function discard(key: string): null {
    localStorage.removeItem(key);
    return null;
}

/**
 * Read a persisted value, returning null — never throwing — if it is missing, unreadable,
 * written by an incompatible version, or no longer the expected shape.
 */
export function readPersisted<T>(
    key: string,
    version: number,
    isValid: (value: unknown) => value is T
): T | null {
    const raw = localStorage.getItem(key);
    if (raw === null) {
        return null;
    }

    let parsed: unknown;
    try {
        parsed = JSON.parse(raw);
    } catch {
        return discard(key);
    }

    if (isEnvelope(parsed)) {
        if (parsed.v !== version) {
            return discard(key);
        }
        return isValid(parsed.data) ? parsed.data : discard(key);
    }

    // Written before this module existed, so there is no version to compare. Keep it if it still
    // matches the current shape — the next write re-saves it inside an envelope — else drop it.
    return isValid(parsed) ? parsed : discard(key);
}

/** Write a value with its version, so a later shape change can recognise and discard it. */
export function writePersisted(key: string, version: number, data: unknown): void {
    localStorage.setItem(key, JSON.stringify({ v: version, data } satisfies Envelope));
}

/**
 * For collections: hydrate the array, then filter the items individually so one bad entry costs
 * the user that entry rather than the whole set.
 */
export function isUnknownArray(value: unknown): value is unknown[] {
    return Array.isArray(value);
}

/** JSON has no Date type, so anything stored as a Date reads back as an ISO string. */
export function reviveDate(value: Date | string): Date {
    return value instanceof Date ? value : new Date(value);
}

/** True when a persisted value will revive into a usable Date. */
export function isRevivableDate(value: unknown): boolean {
    return (
        (typeof value === 'string' || value instanceof Date) &&
        !Number.isNaN(reviveDate(value as Date | string).getTime())
    );
}
