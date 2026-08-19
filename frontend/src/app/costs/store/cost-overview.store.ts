import { patchState, signalStore, withHooks, withMethods, withState } from '@ngrx/signals';
import {
    addEntities,
    addEntity,
    removeEntity,
    updateEntity,
    withEntities,
} from '@ngrx/signals/entities';
import { effect } from '@angular/core';
import { v4 as uuidv4 } from 'uuid';

const CURRENT_STATE_KEY = 'kcc_cost_overview_state';
const SAVED_CONFIGS_KEY = 'kcc_cost_overview_saved_configs';

export interface CostOverviewFormValues {
    from: Date;
    to: Date;
    kafkaStorage: number | null;
    kafkaNetworkRead: number | null;
    kafkaNetworkWrite: number | null;
    total: number | null;
    groupBy: string[];
}

export interface SavedCostConfig extends CostOverviewFormValues {
    id: string;
    name: string;
}

type CostOverviewState = {
    current: CostOverviewFormValues | null;
};

const initialState: CostOverviewState = {
    current: null,
};

function reviveDates<T extends { from: Date; to: Date }>(value: T): T {
    return { ...value, from: new Date(value.from), to: new Date(value.to) };
}

/** Read and parse a stored value, discarding it if it is unreadable rather than throwing. */
function readStored(key: string): unknown {
    const raw = localStorage.getItem(key);
    if (!raw) {
        return null;
    }
    try {
        return JSON.parse(raw);
    } catch {
        localStorage.removeItem(key);
        return null;
    }
}

/**
 * `reviveDates` on an object without `from`/`to` produces Invalid Date, which flows into the form
 * and then into the GraphQL variables, so the shape is checked rather than assumed.
 */
function isCostOverviewValues(value: unknown): value is CostOverviewFormValues {
    if (!value || typeof value !== 'object') {
        return false;
    }
    const candidate = value as Partial<CostOverviewFormValues>;
    return (
        !Number.isNaN(new Date(candidate.from as unknown as string).getTime()) &&
        !Number.isNaN(new Date(candidate.to as unknown as string).getTime())
    );
}

function isSavedConfig(value: unknown): value is SavedCostConfig {
    return (
        isCostOverviewValues(value) &&
        typeof (value as SavedCostConfig).id === 'string' &&
        typeof (value as SavedCostConfig).name === 'string'
    );
}

export const CostOverviewStore = signalStore(
    { providedIn: 'root' },
    withState(initialState),
    withEntities<SavedCostConfig>(),
    withHooks({
        onInit(store) {
            // Both reads are defensive on purpose. This is a root-provided store, so anything
            // thrown here happens during construction and takes the whole Cost Overview route
            // down with no in-app way back — the user would have to clear localStorage by hand.
            // Stored values can also predate a shape change, so a parse succeeding is not enough.
            const storedCurrent = readStored(CURRENT_STATE_KEY);
            if (storedCurrent && isCostOverviewValues(storedCurrent)) {
                patchState(store, { current: reviveDates(storedCurrent) });
            }
            const storedConfigs = readStored(SAVED_CONFIGS_KEY);
            if (Array.isArray(storedConfigs)) {
                const configs = storedConfigs.filter(isSavedConfig).map(reviveDates);
                patchState(store, addEntities(configs));
            }

            effect(() => {
                const current = store.current();
                if (current) {
                    localStorage.setItem(CURRENT_STATE_KEY, JSON.stringify(current));
                }
            });
            effect(() => {
                localStorage.setItem(SAVED_CONFIGS_KEY, JSON.stringify(store.entities()));
            });
        },
    }),
    withMethods(store => ({
        setCurrent(values: CostOverviewFormValues): void {
            patchState(store, { current: values });
        },
        saveConfig(name: string, values: CostOverviewFormValues): string {
            const id = uuidv4();
            patchState(store, addEntity({ ...values, id, name }));
            return id;
        },
        updateConfig(id: string, values: CostOverviewFormValues): void {
            patchState(store, updateEntity({ id, changes: values }));
        },
        deleteConfig(id: string): void {
            patchState(store, removeEntity(id));
        },
    }))
);
