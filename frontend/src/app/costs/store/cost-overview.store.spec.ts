import { TestBed } from '@angular/core/testing';
import { CostOverviewStore } from './cost-overview.store';

const CURRENT_STATE_KEY = 'kcc_cost_overview_state';
const SAVED_CONFIGS_KEY = 'kcc_cost_overview_saved_configs';

/**
 * This is a root-provided store, so anything thrown while hydrating happens during construction
 * and takes the whole Cost Overview route down — with no in-app way back.
 */
describe('CostOverviewStore hydration', () => {
    beforeEach(() => {
        localStorage.removeItem(CURRENT_STATE_KEY);
        localStorage.removeItem(SAVED_CONFIGS_KEY);
        TestBed.resetTestingModule();
    });

    afterEach(() => {
        localStorage.removeItem(CURRENT_STATE_KEY);
        localStorage.removeItem(SAVED_CONFIGS_KEY);
    });

    it('starts fresh when stored state is unparseable', () => {
        localStorage.setItem(CURRENT_STATE_KEY, 'not json');
        localStorage.setItem(SAVED_CONFIGS_KEY, '{{{');

        const store = TestBed.inject(CostOverviewStore);

        expect(store.current()).toBeNull();
        expect(store.entities()).toEqual([]);
    });

    it('rejects stored state whose dates would revive as Invalid Date', () => {
        // reviveDates on an object without from/to yields Invalid Date, which would flow into the
        // form and then into the costOverview GraphQL variables.
        localStorage.setItem(CURRENT_STATE_KEY, JSON.stringify({ groupBy: [] }));

        const store = TestBed.inject(CostOverviewStore);

        expect(store.current()).toBeNull();
    });

    it('ignores saved configs that are not the expected shape', () => {
        localStorage.setItem(
            SAVED_CONFIGS_KEY,
            JSON.stringify([
                { id: 'good', name: 'Keep me', from: '2026-01-01', to: '2026-02-01', groupBy: [] },
                { name: 'no id', from: '2026-01-01', to: '2026-02-01' },
                'not an object',
            ])
        );

        const store = TestBed.inject(CostOverviewStore);

        expect(store.entities().map(e => e.id)).toEqual(['good']);
    });

    it('survives a non-array under the saved configs key', () => {
        localStorage.setItem(SAVED_CONFIGS_KEY, JSON.stringify({ nope: true }));

        const store = TestBed.inject(CostOverviewStore);

        expect(store.entities()).toEqual([]);
    });

    it('still loads a valid stored state', () => {
        localStorage.setItem(
            CURRENT_STATE_KEY,
            JSON.stringify({ from: '2026-01-01T00:00:00Z', to: '2026-02-01T00:00:00Z', groupBy: [] })
        );

        const store = TestBed.inject(CostOverviewStore);

        expect(store.current()?.from).toBeInstanceOf(Date);
    });
});
