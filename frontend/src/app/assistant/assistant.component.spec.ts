import { TestBed } from '@angular/core/testing';
import { ApolloTestingModule } from 'apollo-angular/testing';
import { AssistantComponent } from './assistant.component';

const MESSAGES_KEY = 'kcc_chat_messages';
const SESSION_KEY = 'kcc_chat_session';

describe('AssistantComponent', () => {
    beforeEach(() => {
        localStorage.removeItem(MESSAGES_KEY);
        localStorage.removeItem(SESSION_KEY);
        TestBed.resetTestingModule();
        TestBed.configureTestingModule({
            imports: [AssistantComponent, ApolloTestingModule],
        });
    });

    afterEach(() => {
        localStorage.removeItem(MESSAGES_KEY);
        localStorage.removeItem(SESSION_KEY);
    });

    it('renders the empty state on a first visit', () => {
        const fixture = TestBed.createComponent(AssistantComponent);
        fixture.detectChanges();

        expect(fixture.nativeElement.textContent).toContain('Ask a question');
    });

    it('renders a transcript stored by a version that predates private mode', () => {
        // The regression this guards: the template reads message.columns.length, and a message
        // persisted before those fields existed has them undefined, which took the whole page
        // down rather than just that message.
        localStorage.setItem(
            MESSAGES_KEY,
            JSON.stringify([
                {
                    id: 'legacy-1',
                    role: 'user',
                    text: 'which apps used the most bytes?',
                    generatedSql: [],
                    partial: false,
                    error: null,
                    timestamp: 1,
                },
                {
                    id: 'legacy-2',
                    role: 'assistant',
                    text: 'LogEventAnalyzer used the most.',
                    generatedSql: ['SELECT 1'],
                    partial: false,
                    error: null,
                    timestamp: 2,
                },
            ])
        );

        const fixture = TestBed.createComponent(AssistantComponent);
        fixture.detectChanges();

        const text = fixture.nativeElement.textContent;
        expect(text).toContain('which apps used the most bytes?');
        expect(text).toContain('LogEventAnalyzer used the most.');
    });

    it('renders a result table for a private-mode answer', () => {
        localStorage.setItem(
            MESSAGES_KEY,
            JSON.stringify([
                {
                    id: 'private-1',
                    role: 'assistant',
                    text: 'Here are the results.',
                    generatedSql: ['SELECT application, total FROM t'],
                    columns: ['application', 'total_bytes'],
                    rows: [['LogEventAnalyzer', '25160000000']],
                    truncated: false,
                    partial: false,
                    error: null,
                    timestamp: 3,
                },
            ])
        );

        const fixture = TestBed.createComponent(AssistantComponent);
        fixture.detectChanges();

        const text = fixture.nativeElement.textContent;
        expect(text).toContain('application');
        expect(text).toContain('LogEventAnalyzer');
        expect(text).toContain('1 row');
    });
});
