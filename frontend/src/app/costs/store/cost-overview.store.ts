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
import {
    isRevivableDate,
    isUnknownArray,
    readPersisted,
    reviveDate,
    writePersisted,
} from '../../common/persisted-state';

const CURRENT_STATE_KEY = 'kcc_cost_overview_state';
const SAVED_CONFIGS_KEY = 'kcc_cost_overview_saved_configs';
/** Bump when CostOverviewFormValues changes in a way values written by an older build cannot satisfy. */
const PERSISTED_VERSION = 1;

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
    return { ...value, from: reviveDate(value.from), to: reviveDate(value.to) };
}

/**
 * `reviveDates` on an object without `from`/`to` produces Invalid Date, which flows into the form
 * and then into the GraphQL variables, so the shape is checked rather than assumed. `groupBy` is
 * checked for the same reason: it postdates the first shipped shape, and `cost.component`'s
 * `loadConfig` assigns it straight to a signal that the template then calls `.includes()` on.
 */
function isCostOverviewValues(value: unknown): value is CostOverviewFormValues {
    if (!value || typeof value !== 'object') {
        return false;
    }
    const candidate = value as Partial<CostOverviewFormValues>;
    return (
        Array.isArray(candidate.groupBy) &&
        isRevivableDate(candidate.from) &&
        isRevivableDate(candidate.to)
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
            // Hydration is defensive on purpose: this is a root-provided store, so anything thrown
            // here happens during construction and takes the whole Cost Overview route down with
            // no in-app way back. See common/persisted-state.
            const storedCurrent = readPersisted(
                CURRENT_STATE_KEY,
                PERSISTED_VERSION,
                isCostOverviewValues
            );
            if (storedCurrent) {
                patchState(store, { current: reviveDates(storedCurrent) });
            }
            const storedConfigs = readPersisted(
                SAVED_CONFIGS_KEY,
                PERSISTED_VERSION,
                isUnknownArray
            );
            if (storedConfigs) {
                // filtered per item so one unusable config costs the user that config, not all of them
                patchState(
                    store,
                    addEntities(storedConfigs.filter(isSavedConfig).map(reviveDates))
                );
            }

            effect(() => {
                const current = store.current();
                if (current) {
                    writePersisted(CURRENT_STATE_KEY, PERSISTED_VERSION, current);
                }
            });
            effect(() => {
                writePersisted(SAVED_CONFIGS_KEY, PERSISTED_VERSION, store.entities());
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
