import {patchState, signalStore, withHooks, withMethods, withState} from '@ngrx/signals';
import {Panel} from '../panel.type';
import {v4 as uuidv4} from 'uuid';
import {computed, effect, Signal} from '@angular/core';
import {
    addEntities,
    addEntity,
    removeAllEntities,
    removeEntity,
    updateEntity,
    withEntities
} from '@ngrx/signals/entities';
import {GraphFilter} from '../../tab-graphs/tab-graphs.component';

const PANEL_KEY = 'kcc_panels';

type PanelState = {
    availablePanels: Array<Panel>;
}

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
        }
    ],
}

export const PanelStore = signalStore(
    {providedIn: 'root'},
    withState(initialState),
    withEntities<Panel>(),
    withHooks({
        onInit(store) {
            const jsonPanels = localStorage.getItem(PANEL_KEY);
            if (jsonPanels) {
                patchState(store, addEntities(JSON.parse(jsonPanels)));
            }
            effect(() => {
                // every time store entities (panels) change we save them to localStorage
                store.entityMap()
                // we ignore eChartsInstance when serializing, this gets set again when eCharts instantiates
                const serializable = store.entities()
                    .map(panel => ({...panel, eChartsInstance: undefined}));
                localStorage.setItem(PANEL_KEY, JSON.stringify(serializable));
            });
        }
    }),
    withMethods((store) => ({
        addPanel(panel: Panel): void {
            patchState(store, addEntity({...panel, id: uuidv4()}));
        },
        updatePanel(id: string, panel: Partial<Panel>): void {
            patchState(store, updateEntity({id: id, changes: panel}));
        },
        movePanelRight(id: string): void {
            const index = store.entities().findIndex(panel => panel.id === id);
            if (index === -1 || index === store.entities().length - 1) {
                return;
            }
            const newPanels = [...store.entities()];
            [newPanels[index], newPanels[index + 1]] = [{...newPanels[index + 1]}, {...newPanels[index]}];
            patchState(store,
                removeAllEntities(),
                addEntities(newPanels),
            )
        },
        movePanelLeft(id: string): void {
            const index = store.entities().findIndex(panel => panel.id === id);
            if (index === -1 || index === 0) {
                return;
            }
            const newPanels = [...store.entities()];
            [newPanels[index - 1], newPanels[index]] = [{...newPanels[index]}, {...newPanels[index - 1]}];
            patchState(store,
                removeAllEntities(),
                addEntities(newPanels),
            )
        },
        removePanel(id: string): void {
            patchState(store, removeEntity(id));
        },
        filter(id: Signal<string>): Signal<GraphFilter> {
            return computed(() => {
                const panel = store.entityMap()[id()];
                return {
                    from: panel.from,
                    to: panel.to,
                    metricName: panel.metricName,
                    groupByContext: panel.groupByContext
                };
            });
        },
    }))
);
