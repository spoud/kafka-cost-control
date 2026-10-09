import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MetricHistory } from '../../../../../generated/graphql/types';
import { GraphFilterService } from '../../../../tab-graphs/graph-filter/graph-filter.service';
import { PanelStore } from '../../../store/panel.store';
import { PieChartPanelComponent } from './pie-chart-panel.component';

const history = [
    {
        name: 'tenant=a',
        times: ['2026-10-01T00:00:00Z'],
        values: [2048],
        costs: [0.3],
        context: [],
    },
] as unknown as MetricHistory[];

function chartOf(showCost: boolean) {
    localStorage.removeItem('kcc_panels');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
        providers: [
            {
                provide: GraphFilterService,
                useValue: { historyResource: () => ({ value: signal(history) }) },
            },
        ],
    });
    const store = TestBed.inject(PanelStore);
    const id = store.addPanel();
    store.updatePanel(id, { showCost });
    const fixture = TestBed.createComponent(PieChartPanelComponent);
    // not rendered: ECharts needs a ResizeObserver jsdom lacks; the data is what's under test
    fixture.componentRef.setInput('id', id);
    return fixture.componentInstance as unknown as {
        chartData(): MetricHistory[];
        unit(): string;
    };
}

describe('A Reporting panel', () => {
    afterEach(() => localStorage.removeItem('kcc_panels'));

    it('plots the usage by default', () => {
        const panel = chartOf(false);

        expect(panel.unit()).toBe('usage');
        expect(panel.chartData()[0].values).toEqual([2048]);
    });

    it('plots the cost, in currency, when set to', () => {
        const panel = chartOf(true);

        expect(panel.unit()).toBe('currency');
        expect(panel.chartData()[0].values).toEqual([0.3]);
    });
});
