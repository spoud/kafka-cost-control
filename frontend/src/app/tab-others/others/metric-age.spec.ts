import { deleteMessage, stillArriving } from './metric-age';

describe('metric age', () => {
    const now = new Date('2026-10-14T12:00:00Z');

    it('counts a metric seen in the last hours as still arriving', () => {
        expect(stillArriving('2026-10-14T11:00:00Z', now)).toBe(true);
        expect(stillArriving('2026-10-14T08:00:00Z', now)).toBe(false);
        expect(stillArriving(null, now)).toBe(false);
    });

    it('warns that a metric still being collected comes back', () => {
        expect(deleteMessage('2026-10-14T11:00:00Z', now)).toContain('still being collected');
        expect(deleteMessage('2026-10-01T16:00:00Z', now)).not.toContain('still being collected');
        expect(deleteMessage('2026-10-01T16:00:00Z', now)).toContain(
            'a reprocess covering its time brings it back'
        );
    });
});
