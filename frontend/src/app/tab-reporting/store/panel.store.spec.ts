import { TestBed } from '@angular/core/testing';
import { PanelStore } from './panel.store';

const PANEL_KEY = 'kcc_panels';

const validPanel = {
    id: 'p1',
    title: 'Bar Chart',
    type: 'StackedBar',
    from: '2026-01-01T00:00:00Z',
    groupByContext: [],
};

/**
 * This is a root-provided store, so anything thrown while hydrating happens during construction
 * and takes the whole Reporting route down — with no in-app way back.
 */
describe('PanelStore hydration', () => {
    beforeEach(() => {
        localStorage.removeItem(PANEL_KEY);
        TestBed.resetTestingModule();
    });

    afterEach(() => localStorage.removeItem(PANEL_KEY));

    it('starts fresh when stored panels are unparseable', () => {
        localStorage.setItem(PANEL_KEY, 'not json');

        expect(TestBed.inject(PanelStore).entities()).toEqual([]);
    });

    it('survives a non-array under the panels key', () => {
        localStorage.setItem(PANEL_KEY, JSON.stringify({ nope: true }));

        expect(TestBed.inject(PanelStore).entities()).toEqual([]);
    });

    it('drops a panel whose chart type no longer exists', () => {
        // an unknown type resolves to an undefined component and renders as a blank card
        localStorage.setItem(
            PANEL_KEY,
            JSON.stringify([validPanel, { ...validPanel, id: 'p2', type: 'Removed' }])
        );

        expect(
            TestBed.inject(PanelStore)
                .entities()
                .map(p => p.id)
        ).toEqual(['p1']);
    });

    it('drops a panel missing required fields rather than the whole board', () => {
        localStorage.setItem(
            PANEL_KEY,
            JSON.stringify([validPanel, { id: 'p3', type: 'Pie' }, 'not an object'])
        );

        expect(
            TestBed.inject(PanelStore)
                .entities()
                .map(p => p.id)
        ).toEqual(['p1']);
    });

    it('revives from/to as Dates, not the strings JSON gives back', () => {
        localStorage.setItem(
            PANEL_KEY,
            JSON.stringify([{ ...validPanel, to: '2026-02-01T00:00:00Z' }])
        );

        const [panel] = TestBed.inject(PanelStore).entities();
        expect(panel.from).toBeInstanceOf(Date);
        expect(panel.to).toBeInstanceOf(Date);
    });

    it('leaves an absent to undefined rather than an Invalid Date', () => {
        localStorage.setItem(PANEL_KEY, JSON.stringify([validPanel]));

        expect(TestBed.inject(PanelStore).entities()[0].to).toBeUndefined();
    });
});
