import { patchState, signalStore, withHooks, withMethods, withState } from '@ngrx/signals';
import { addEntities, addEntity, removeEntity, withEntities } from '@ngrx/signals/entities';
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

export const CostOverviewStore = signalStore(
    { providedIn: 'root' },
    withState(initialState),
    withEntities<SavedCostConfig>(),
    withHooks({
        onInit(store) {
            const storedCurrent = localStorage.getItem(CURRENT_STATE_KEY);
            if (storedCurrent) {
                patchState(store, { current: reviveDates(JSON.parse(storedCurrent)) });
            }
            const storedConfigs = localStorage.getItem(SAVED_CONFIGS_KEY);
            if (storedConfigs) {
                const configs: SavedCostConfig[] = JSON.parse(storedConfigs).map(reviveDates);
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
        saveConfig(name: string, values: CostOverviewFormValues): void {
            patchState(store, addEntity({ ...values, id: uuidv4(), name }));
        },
        deleteConfig(id: string): void {
            patchState(store, removeEntity(id));
        },
    }))
);
