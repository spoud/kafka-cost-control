/** A closed date range. Both ends are inclusive. */
export interface DateRange {
    from: Date;
    to: Date;
}

/** Midnight at the start of `date`'s day. */
export function startOfDay(date: Date): Date {
    const d = new Date(date);
    d.setHours(0, 0, 0, 0);
    return d;
}

/**
 * The last instant of `date`'s day. A date picker yields midnight and a range is read
 * inclusively, so a `to` taken straight from one would exclude the day the user selected.
 */
export function endOfDay(date: Date): Date {
    const d = new Date(date);
    d.setHours(23, 59, 59, 999);
    return d;
}

/** True when both dates fall on the same calendar day. */
export function isSameDay(a: Date, b: Date): boolean {
    return (
        a.getFullYear() === b.getFullYear() &&
        a.getMonth() === b.getMonth() &&
        a.getDate() === b.getDate()
    );
}

/**
 * The picked calendar days as a UTC range: from midnight UTC of the first day to midnight UTC after
 * the last (exclusive). Bills are per UTC month, like the provider's, so a range read in local time
 * would reach into the neighbouring month (1 October in Zurich starts at 30 September 22:00 UTC).
 */
export function utcDayRange(range: DateRange): { from: Date; to: Date } {
    return {
        from: new Date(
            Date.UTC(range.from.getFullYear(), range.from.getMonth(), range.from.getDate())
        ),
        to: new Date(Date.UTC(range.to.getFullYear(), range.to.getMonth(), range.to.getDate() + 1)),
    };
}
