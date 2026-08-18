import {
    ChangeDetectionStrategy,
    Component,
    ElementRef,
    inject,
    signal,
    viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButton, MatIconButton } from '@angular/material/button';
import { MatFormField, MatLabel } from '@angular/material/form-field';
import { MatIcon } from '@angular/material/icon';
import { MatInput } from '@angular/material/input';
import { MatTooltip } from '@angular/material/tooltip';
import { PageHeaderComponent } from '../common/page-header/page-header.component';
import { EmptyStateComponent } from '../common/empty-state/empty-state.component';
import { LoadingIndicatorComponent } from '../common/loading-indicator/loading-indicator.component';
import { ChatStore } from './store/chat.store';
import { AssistantStatusService } from './assistant-status.service';
import { ChatGQL } from '../../generated/graphql/sdk';

/** Shown as clickable starters when the transcript is empty. */
const SUGGESTIONS = [
    'Which applications used the most bytes last week?',
    'How much data is retained per topic right now?',
    'Show the top 5 topics by traffic over the last 7 days',
];

@Component({
    selector: 'app-assistant',
    imports: [
        FormsModule,
        MatButton,
        MatIconButton,
        MatFormField,
        MatLabel,
        MatIcon,
        MatInput,
        MatTooltip,
        PageHeaderComponent,
        EmptyStateComponent,
        LoadingIndicatorComponent,
    ],
    templateUrl: './assistant.component.html',
    styleUrl: './assistant.component.scss',
    changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AssistantComponent {
    private readonly chatGql = inject(ChatGQL);
    protected readonly store = inject(ChatStore);
    protected readonly status = inject(AssistantStatusService);

    protected readonly suggestions = SUGGESTIONS;
    protected readonly draft = signal('');
    /** Ids of assistant messages whose SQL block is expanded. */
    protected readonly expandedSql = signal<ReadonlySet<string>>(new Set());

    private readonly transcript = viewChild<ElementRef<HTMLElement>>('transcript');

    protected send(): void {
        const question = this.draft().trim();
        if (!question || this.store.pending()) {
            return;
        }

        this.store.addUserMessage(question);
        this.draft.set('');
        this.store.setPending(true);
        this.scrollToBottom();

        this.chatGql
            .mutate({ variables: { sessionId: this.store.sessionId(), message: question } })
            .subscribe({
                next: response => {
                    const answer = response.data?.chat;
                    if (!answer) {
                        this.store.addAssistantMessage({
                            text: '',
                            generatedSql: [],
                            columns: [],
                            rows: [],
                            truncated: false,
                            partial: false,
                            error: 'The server returned an empty response.',
                        });
                    } else {
                        this.store.addAssistantMessage({
                            text: answer.text,
                            generatedSql: [...(answer.generatedSql ?? [])],
                            columns: [...(answer.columns ?? [])],
                            rows: (answer.rows ?? []).map(row => [...row]),
                            truncated: answer.truncated,
                            partial: !answer.complete && !answer.error,
                            error: answer.error ?? null,
                        });
                    }
                    this.store.setPending(false);
                    this.scrollToBottom();
                },
                error: (err: unknown) => {
                    this.store.addAssistantMessage({
                        text: '',
                        generatedSql: [],
                        columns: [],
                        rows: [],
                        truncated: false,
                        partial: false,
                        error: err instanceof Error ? err.message : 'The request failed.',
                    });
                    this.store.setPending(false);
                    this.scrollToBottom();
                },
            });
    }

    protected useSuggestion(suggestion: string): void {
        this.draft.set(suggestion);
        this.send();
    }

    protected onKeydown(event: KeyboardEvent): void {
        // Enter sends, Shift+Enter inserts a newline — the convention users expect from a chat box.
        if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault();
            this.send();
        }
    }

    protected toggleSql(messageId: string): void {
        const next = new Set(this.expandedSql());
        if (!next.delete(messageId)) {
            next.add(messageId);
        }
        this.expandedSql.set(next);
    }

    protected isSqlExpanded(messageId: string): boolean {
        return this.expandedSql().has(messageId);
    }

    protected reset(): void {
        this.store.reset();
        this.expandedSql.set(new Set());
    }

    private scrollToBottom(): void {
        // Wait a frame so the new message is in the DOM before measuring.
        requestAnimationFrame(() => {
            const el = this.transcript()?.nativeElement;
            if (el) {
                el.scrollTop = el.scrollHeight;
            }
        });
    }
}
