import { TestBed } from '@angular/core/testing';
import { BarChartComponent } from './bar-chart.component';
import { MetricHistory } from '../../../../generated/graphql/types';

function series(name: string, times: string[], values: (number | null)[]): MetricHistory {
    return { name, times, values, context: [] } as unknown as MetricHistory;
}

function datasetOf(metricsData: MetricHistory[]): unknown[][] {
    const fixture = TestBed.createComponent(BarChartComponent);
    fixture.componentRef.setInput('metricsData', metricsData);
    fixture.componentRef.setInput('type', 'line');
    const options = fixture.componentInstance.options() as { dataset: { source: unknown[][] } };
    return options.dataset.source;
}

describe('BarChartComponent dataset', () => {
    beforeEach(() => {
        // ThemeService reads prefers-color-scheme in its constructor and jsdom has no matchMedia
        window.matchMedia = ((query: string) => ({
            matches: false,
            media: query,
            onchange: null,
            addEventListener: () => undefined,
            removeEventListener: () => undefined,
            addListener: () => undefined,
            removeListener: () => undefined,
            dispatchEvent: () => false,
        })) as unknown as typeof window.matchMedia;
        TestBed.resetTestingModule();
    });

    it('keeps a measured zero rather than turning it into a hole', () => {
        // The old truthiness check treated 0 as "no reading", so a series that legitimately went
        // to zero was punched full of nulls - and a series that was only ever zero vanished.
        expect(
            datasetOf([series('a', ['2026-01-01T00:00:00Z', '2026-01-01T01:00:00Z'], [0, 5])])
        ).toEqual([
            ['2026-01-01T00:00:00Z', 0],
            ['2026-01-01T01:00:00Z', 5],
        ]);
    });

    it('still records a genuinely missing point as null', () => {
        // `b` has no reading at the first timestamp, which is a hole and must stay one
        expect(
            datasetOf([
                series('a', ['2026-01-01T00:00:00Z', '2026-01-01T01:00:00Z'], [1, 2]),
                series('b', ['2026-01-01T01:00:00Z'], [3]),
            ])
        ).toEqual([
            ['2026-01-01T00:00:00Z', 1, null],
            ['2026-01-01T01:00:00Z', 2, 3],
        ]);
    });

    it('breaks the line where whole buckets are missing', () => {
        // hourly buckets with 02:00 and 03:00 absent from every series: there is no row for them
        // at all, so without an inserted break the time axis joins 01:00 straight to 04:00
        const rows = datasetOf([
            series(
                'a',
                [
                    '2026-01-01T00:00:00Z',
                    '2026-01-01T01:00:00Z',
                    '2026-01-01T04:00:00Z',
                    '2026-01-01T05:00:00Z',
                ],
                [1, 2, 3, 4]
            ),
        ]);

        expect(rows).toEqual([
            ['2026-01-01T00:00:00Z', 1],
            ['2026-01-01T01:00:00Z', 2],
            ['2026-01-01T02:00:00.000Z', null],
            ['2026-01-01T04:00:00Z', 3],
            ['2026-01-01T05:00:00Z', 4],
        ]);
    });

    it('leaves evenly sampled data untouched', () => {
        const rows = datasetOf([
            series(
                'a',
                ['2026-01-01T00:00:00Z', '2026-01-01T01:00:00Z', '2026-01-01T02:00:00Z'],
                [1, 2, 3]
            ),
        ]);

        expect(rows).toHaveLength(3);
    });

    it('does not bridge holes unless asked', () => {
        const fixture = TestBed.createComponent(BarChartComponent);
        fixture.componentRef.setInput('metricsData', [series('a', ['t1'], [1])]);
        fixture.componentRef.setInput('type', 'line');
        const read = () =>
            (fixture.componentInstance.options() as { series: { connectNulls: boolean }[] })
                .series[0].connectNulls;

        expect(read()).toBe(false);

        fixture.componentInstance.connectNulls.set(true);
        expect(read()).toBe(true);
    });
});
