import { applyLegendClick } from './legend-selection';

const all = ['alpha', 'beta', 'gamma'];
const click = (name: string) => ({ name, additive: false });
const shiftClick = (name: string) => ({ name, additive: true });

describe('applyLegendClick', () => {
    it('isolates the clicked series', () => {
        expect([...applyLegendClick(new Set(), all, click('beta'))].sort()).toEqual([
            'alpha',
            'gamma',
        ]);
    });

    it('restores everything when the already-isolated series is clicked again', () => {
        const isolated = applyLegendClick(new Set(), all, click('beta'));

        expect([...applyLegendClick(isolated, all, click('beta'))]).toEqual([]);
    });

    it('switches isolation when a different series is clicked', () => {
        const isolated = applyLegendClick(new Set(), all, click('beta'));

        expect([...applyLegendClick(isolated, all, click('gamma'))].sort()).toEqual([
            'alpha',
            'beta',
        ]);
    });

    it('shift-click hides only the clicked series', () => {
        expect([...applyLegendClick(new Set(), all, shiftClick('beta'))]).toEqual(['beta']);
    });

    it('shift-click on a hidden series brings it back', () => {
        const hidden = applyLegendClick(new Set(), all, shiftClick('beta'));

        expect([...applyLegendClick(hidden, all, shiftClick('beta'))]).toEqual([]);
    });

    it('does not treat a lone remaining series as isolated when others were shift-hidden', () => {
        // alpha and gamma hidden one at a time leaves beta alone; clicking beta should still
        // isolate-then-restore rather than being a no-op
        let state = applyLegendClick(new Set(), all, shiftClick('alpha'));
        state = applyLegendClick(state, all, shiftClick('gamma'));

        expect([...applyLegendClick(state, all, click('beta'))]).toEqual([]);
    });
});
