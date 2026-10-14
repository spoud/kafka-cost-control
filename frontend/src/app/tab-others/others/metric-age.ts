/**
 * How recent a metric's last stored hour must be to count as still collected: an hour's window
 * ends an hour after it starts, and the collector adds some delay.
 */
const STILL_ARRIVING_MS = 3 * 60 * 60 * 1000;

/** Whether a metric last seen at {@code lastSeen} is still being collected. */
export function stillArriving(
    lastSeen: string | null | undefined,
    now: Date = new Date()
): boolean {
    if (!lastSeen) {
        return false;
    }
    return now.getTime() - new Date(lastSeen).getTime() < STILL_ARRIVING_MS;
}

/** What the delete dialog tells about a metric. */
export function deleteMessage(lastSeen: string | null | undefined, now: Date = new Date()): string {
    const what =
        'All its stored data is deleted: it disappears from every page, chart, export and the assistant. ' +
        "The raw metrics aren't touched, so a reprocess covering its time brings it back.";
    if (stillArriving(lastSeen, now)) {
        return (
            what +
            ' It is still being collected: new data keeps coming in until the collector stops sending it.'
        );
    }
    return what;
}
