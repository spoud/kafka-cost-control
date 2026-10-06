import { TestBed } from '@angular/core/testing';
import { SankeyComponent } from './sankey.component';
import { BilledCostsQuery } from '../../../generated/graphql/sdk';

type Node = { name: string; itemStyle: { color: string } };
type Costs = BilledCostsQuery['billedCosts'];
type Series = {
    series: {
        data: Node[];
        links: { source: string; target: string; value: number }[];
        label: { formatter: (params: { name?: string }) => string };
    };
};

function render(costs: Costs, groupBy: string[]) {
    const fixture = TestBed.createComponent(SankeyComponent);
    fixture.componentRef.setInput('inputData', costs);
    fixture.componentRef.setInput('groupBy', groupBy);
    return fixture.componentInstance;
}

function build(entryCount: number) {
    const shares = Array.from({ length: entryCount }, (_, i) => ({
        name: `tenant=t${i % 3} › application=app${i}`,
        price: (entryCount - i) * 100,
        estimatedPrice: 0,
        contextValues: [`t${i % 3}`, `app${i}`],
    }));
    const sankey = render(
        { metrics: [{ metric: 'confluent_kafka_server_retained_bytes', shares }], months: [] },
        ['tenant', 'application']
    );
    return { options: sankey.sankeyOptions() as Series, height: sankey.chartHeight() };
}

describe('SankeyComponent', () => {
    beforeEach(() => {
        // No matchMedia stub needed: ThemeService optional-chains it, so it falls back to light
        // under jsdom, which is the palette these assertions expect.
        TestBed.resetTestingModule();
    });

    it('draws every entry instead of rolling the tail into an "N smaller" node', () => {
        // 40 entries is well past the old 25-entry cap that produced the roll-up node
        const names = build(40).options.series.data.map(n => n.name);

        expect(names.some(n => /smaller/.test(n))).toBe(false);
        expect(names.filter(n => /application=app\d+$/.test(n))).toHaveLength(40);
    });

    it('grows the canvas with the busiest column so labels have room', () => {
        const small = build(4).height;
        const large = build(60).height;

        expect(large).toBeGreaterThan(small);
        expect(small).toBeGreaterThanOrEqual(600);
    });

    it('gives neighbouring nodes distinct colours', () => {
        // the shared chart palette only has 8 entries, so it repeated every 8 nodes
        const colors = build(40).options.series.data.map(n => n.itemStyle.color);

        expect(new Set(colors.slice(0, 24)).size).toBe(24);
        // zrender splits colour params on commas; the space-separated CSS form renders black
        colors.forEach(c => expect(c).toMatch(/^hsl\(\d+(\.\d+)?, \d+%, \d+%\)$/));
    });

    it('roots each cost at the sum of its shares, the shared part included', () => {
        const costs: Costs = {
            metrics: [
                {
                    metric: 'confluent_kafka_server_request_bytes',
                    shares: [
                        { name: 'tenant=a', price: 250, estimatedPrice: 0, contextValues: ['a'] },
                        { name: 'tenant=b', price: 150, estimatedPrice: 50, contextValues: ['b'] },
                    ],
                },
                {
                    metric: 'platform',
                    shares: [
                        {
                            name: 'Platform / shared',
                            price: 100,
                            estimatedPrice: 0,
                            contextValues: ['<platform>'],
                        },
                    ],
                },
            ],
            months: [],
        };
        const { series } = render(costs, ['tenant']).sankeyOptions() as Series;
        const fromTotal = (target: string) =>
            series.links.find(l => l.source === 'total' && l.target === target)?.value;

        expect(fromTotal('confluent_kafka_server_request_bytes')).toBe(4);
        expect(fromTotal('platform')).toBe(1);
        // readable names for the bill's lines
        expect(series.label.formatter({ name: 'confluent_kafka_server_request_bytes' })).toBe(
            'Network write'
        );
        expect(series.label.formatter({ name: 'platform' })).toBe('Platform / shared');
    });
});
