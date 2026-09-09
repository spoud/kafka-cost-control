/**
 * Helpers for state persisted to localStorage.
 *
 * Reads must not throw: the stores using this are `providedIn: 'root'` and hydrate during
 * construction, so anything thrown takes down the whole route. Every read goes through a type
 * guard, every storage access is guarded, and every write carries a version so a breaking shape
 * change can discard old values by bumping it.
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
    try {
        localStorage.removeItem(key);
    } catch {
        // storage blocked; the caller already treats the value as absent
    }
    return null;
}

/**
 * Read a persisted value. Returns null, never throws, if it is missing, unreadable, written by an
 * incompatible version, or no longer the expected shape.
 */
export function readPersisted<T>(
    key: string,
    version: number,
    isValid: (value: unknown) => value is T
): T | null {
    let raw: string | null;
    try {
        raw = localStorage.getItem(key);
    } catch {
        return null;
    }
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
    try {
        localStorage.setItem(key, JSON.stringify({ v: version, data } satisfies Envelope));
    } catch {
        // quota exceeded, or storage blocked
    }
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
