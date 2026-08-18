import { TestBed } from '@angular/core/testing';
import { ChatStore } from './chat.store';

const MESSAGES_KEY = 'kcc_chat_messages';
const SESSION_KEY = 'kcc_chat_session';

/**
 * The transcript is persisted in localStorage, so it outlives the code that wrote it. These
 * tests cover reading back a transcript written by an older version of the app.
 */
describe('ChatStore hydration', () => {
    beforeEach(() => {
        localStorage.removeItem(MESSAGES_KEY);
        localStorage.removeItem(SESSION_KEY);
        TestBed.resetTestingModule();
    });

    afterEach(() => {
        localStorage.removeItem(MESSAGES_KEY);
        localStorage.removeItem(SESSION_KEY);
    });

    it('fills in fields missing from a transcript written before private mode existed', () => {
        // Exactly the shape stored by the version before columns/rows/truncated were added.
        // The template reads message.columns.length, so leaving these undefined crashes the
        // whole page on render rather than degrading.
        localStorage.setItem(
            MESSAGES_KEY,
            JSON.stringify([
                {
                    id: 'legacy-1',
                    role: 'assistant',
                    text: 'an answer from an older version',
                    generatedSql: ['SELECT 1'],
                    partial: false,
                    error: null,
                    timestamp: 1,
                },
            ])
        );

        const store = TestBed.inject(ChatStore);
        const [message] = store.entities();

        expect(message.text).toBe('an answer from an older version');
        expect(message.generatedSql).toEqual(['SELECT 1']);
        expect(message.columns).toEqual([]);
        expect(message.rows).toEqual([]);
        expect(message.truncated).toBe(false);
    });

    it('survives a transcript that is not an array', () => {
        localStorage.setItem(MESSAGES_KEY, JSON.stringify({ nonsense: true }));

        const store = TestBed.inject(ChatStore);

        expect(store.entities()).toEqual([]);
    });

    it('starts fresh when the transcript is unparseable', () => {
        localStorage.setItem(MESSAGES_KEY, 'not json at all');

        const store = TestBed.inject(ChatStore);

        expect(store.entities()).toEqual([]);
    });

    it('keeps the session id so the backend does not lose the conversation on reload', () => {
        localStorage.setItem(SESSION_KEY, 'session-from-a-previous-visit');

        const store = TestBed.inject(ChatStore);

        expect(store.sessionId()).toBe('session-from-a-previous-visit');
    });
});
