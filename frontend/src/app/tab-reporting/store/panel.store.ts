import { patchState, signalStore, withHooks, withMethods, withState } from '@ngrx/signals';
import { isPanel, Panel } from '../panel.type';
import {
    isUnknownArray,
    readPersisted,
    reviveDate,
    writePersisted,
} from '../../common/persisted-state';
import { v4 as uuidv4 } from 'uuid';
import { computed, effect, Signal } from '@angular/core';
import {
    addEntities,
    addEntity,
    removeAllEntities,
    removeEntity,
    updateEntity,
    withEntities,
} from '@ngrx/signals/entities';
import { GraphFilter } from '../../tab-graphs/tab-graphs.component';

const PANEL_KEY = 'kcc_panels';
/** Bump when Panel changes in a way values written by an older build cannot satisfy. */
const PERSISTED_VERSION = 1;

/** `from`/`to` are Dates in the type but ISO strings once they have been through localStorage. */
function revivePanelDates(panel: Panel): Panel {
    return {
        ...panel,
        from: reviveDate(panel.from),
        to: panel.to === undefined ? undefined : reviveDate(panel.to),
    };
}

type PanelState = {
    availablePanels: Array<Panel>;
};

const ONE_WEEK = 7 * 24 * 60 * 60 * 1000;
const DEFAULT_FROM = new Date(new Date(new Date().getTime() - ONE_WEEK).setUTCHours(0, 0, 0, 0));

const initialState: PanelState = {
    availablePanels: [
        {
            id: '1a',
            title: 'Bar Chart',
            description: 'A stacked bar chart',
            type: 'StackedBar',
            from: DEFAULT_FROM,
            groupByContext: [],
        },
        {
            id: '1b',
            title: 'Area Chart',
            description: 'A stacked area chart',
            type: 'Line',
            from: DEFAULT_FROM,
            groupByContext: [],
        },
        {
            id: '2',
            title: 'Pie Chart',
            type: 'Pie',
            from: DEFAULT_FROM,
            groupByContext: [],
        },
    ],
};

export const PanelStore = signalStore(
    { providedIn: 'root' },
    withState(initialState),
    withEntities<Panel>(),
    withHooks({
        onInit(store) {
            // Hydration is defensive on purpose: this is a root-provided store, so anything thrown
            // here happens during construction and takes the whole Reporting route down with no
            // in-app way back. See common/persisted-state.
            const storedPanels = readPersisted(PANEL_KEY, PERSISTED_VERSION, isUnknownArray);
            if (storedPanels) {
                // filtered per item so one unusable panel costs the user that panel, not the board
                patchState(store, addEntities(storedPanels.filter(isPanel).map(revivePanelDates)));
            }
            effect(() => {
                // every time store entities (panels) change we save them to localStorage
                // we ignore eChartsInstance when serializing, this gets set again when eCharts instantiates
                const serializable = store
                    .entities()
                    .map(panel => ({ ...panel, eChartsInstance: undefined }));
                writePersisted(PANEL_KEY, PERSISTED_VERSION, serializable);
            });
        },
    }),
    withMethods(store => ({
        addPanel(panel: Panel): void {
            patchState(store, addEntity({ ...panel, id: uuidv4() }));
        },
        updatePanel(id: string, panel: Partial<Panel>): void {
            patchState(store, updateEntity({ id: id, changes: panel }));
        },
        movePanelRight(id: string): void {
            const index = store.entities().findIndex(panel => panel.id === id);
            if (index === -1 || index === store.entities().length - 1) {
                return;
            }
            const newPanels = [...store.entities()];
            [newPanels[index], newPanels[index + 1]] = [
                { ...newPanels[index + 1] },
                { ...newPanels[index] },
            ];
            patchState(store, removeAllEntities(), addEntities(newPanels));
        },
        movePanelLeft(id: string): void {
            const index = store.entities().findIndex(panel => panel.id === id);
            if (index === -1 || index === 0) {
                return;
            }
            const newPanels = [...store.entities()];
            [newPanels[index - 1], newPanels[index]] = [
                { ...newPanels[index] },
                { ...newPanels[index - 1] },
            ];
            patchState(store, removeAllEntities(), addEntities(newPanels));
        },
        removePanel(id: string): void {
            patchState(store, removeEntity(id));
        },
        filter(id: Signal<string>): Signal<GraphFilter> {
            return computed(
                () => {
                    const panel = store.entityMap()[id()];
                    return {
                        from: panel.from,
                        to: panel.to,
                        metricName: panel.metricName,
                        groupByContext: panel.groupByContext,
                    };
                },
                { equal: (a, b) => JSON.stringify(a) === JSON.stringify(b) }
            );
        },
    }))
);
