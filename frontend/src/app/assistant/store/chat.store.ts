import { patchState, signalStore, withHooks, withMethods, withState } from '@ngrx/signals';
import { addEntities, addEntity, removeAllEntities, withEntities } from '@ngrx/signals/entities';
import { effect } from '@angular/core';
import { v4 as uuidv4 } from 'uuid';
import {
    isUnknownArray,
    readPersisted,
    readRaw,
    writePersisted,
    writeRaw,
} from '../../common/persisted-state';

const MESSAGES_KEY = 'kcc_chat_messages';
const SESSION_KEY = 'kcc_chat_session';

/** Bound the stored transcript: localStorage is small and shared with every other kcc_* key. */
const MAX_STORED_MESSAGES = 100;
/** Bump when ChatMessage changes in a way a transcript from an older build cannot satisfy. */
const PERSISTED_VERSION = 1;

export type ChatRole = 'user' | 'assistant';

export interface ChatMessage {
    id: string;
    role: ChatRole;
    text: string;
    /** SQL the assistant ran to produce this answer, shown collapsed under the reply. */
    generatedSql: string[];
    /** Result table. Populated in private mode, where the table is the answer. */
    columns: string[];
    rows: (string | null)[][];
    /** True when the result hit the row cap and is not the whole story. */
    truncated: boolean;
    /** Backend had no memory of this conversation though we were showing earlier questions. */
    contextLost: boolean;
    /** True when the assistant stopped before reaching an answer (step limit). */
    partial: boolean;
    /** Set when the request failed outright; rendered as an error rather than a reply. */
    error: string | null;
    timestamp: number;
}

type ChatState = {
    /** Conversation id. The backend keys history on it, so it must survive a reload. */
    sessionId: string;
    pending: boolean;
};

const initialState: ChatState = {
    sessionId: uuidv4(),
    pending: false,
};

/**
 * Normalise messages read back from localStorage. A stored transcript can predate fields added
 * since; rendering reads them directly, so a missing one crashes the page rather than degrading.
 */
function hydrateMessages(stored: unknown): ChatMessage[] {
    if (!Array.isArray(stored)) {
        return [];
    }
    return stored
        .filter((m): m is Partial<ChatMessage> => !!m && typeof m === 'object')
        .map(m => ({
            id: m.id ?? uuidv4(),
            role: m.role === 'assistant' ? 'assistant' : 'user',
            text: m.text ?? '',
            generatedSql: m.generatedSql ?? [],
            columns: m.columns ?? [],
            rows: m.rows ?? [],
            truncated: m.truncated ?? false,
            contextLost: m.contextLost ?? false,
            partial: m.partial ?? false,
            error: m.error ?? null,
            timestamp: m.timestamp ?? Date.now(),
        }));
}

export const ChatStore = signalStore(
    { providedIn: 'root' },
    withState(initialState),
    withEntities<ChatMessage>(),
    withHooks({
        onInit(store) {
            // Through common/persisted-state, like every other store: the access itself throws
            // when a browser blocks site data, and this store is root-provided, so an unguarded
            // read here takes the page down during construction.
            const storedSession = readRaw(SESSION_KEY);
            if (storedSession) {
                patchState(store, { sessionId: storedSession });
            } else {
                writeRaw(SESSION_KEY, store.sessionId());
            }

            const storedMessages = readPersisted(MESSAGES_KEY, PERSISTED_VERSION, isUnknownArray);
            if (storedMessages) {
                // hydrated per item, so one unusable message costs that message, not the transcript
                patchState(store, addEntities(hydrateMessages(storedMessages)));
            }

            effect(() => {
                writePersisted(
                    MESSAGES_KEY,
                    PERSISTED_VERSION,
                    store.entities().slice(-MAX_STORED_MESSAGES)
                );
            });
            effect(() => {
                writeRaw(SESSION_KEY, store.sessionId());
            });
        },
    }),
    withMethods(store => ({
        addUserMessage(text: string): void {
            const message: ChatMessage = {
                id: uuidv4(),
                role: 'user',
                text,
                generatedSql: [],
                columns: [],
                rows: [],
                truncated: false,
                contextLost: false,
                partial: false,
                error: null,
                timestamp: Date.now(),
            };
            patchState(store, addEntity(message));
        },
        addAssistantMessage(answer: Omit<ChatMessage, 'id' | 'role' | 'timestamp'>): void {
            const message: ChatMessage = {
                ...answer,
                id: uuidv4(),
                role: 'assistant',
                timestamp: Date.now(),
            };
            patchState(store, addEntity(message));
        },
        setPending(pending: boolean): void {
            patchState(store, { pending });
        },
        /** Clear the transcript and start a new backend conversation. */
        reset(): void {
            patchState(store, removeAllEntities());
            patchState(store, { sessionId: uuidv4() });
        },
    }))
);
