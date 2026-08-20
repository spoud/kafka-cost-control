import { TestBed } from '@angular/core/testing';
import { SankeyComponent } from './sankey.component';
import { CostOverviewQuery } from '../../../generated/graphql/sdk';
import { CostOverviewRequestInput } from '../../../generated/graphql/types';

type Node = { name: string; itemStyle: { color: string } };

function build(entryCount: number) {
    const fixture = TestBed.createComponent(SankeyComponent);
    const nameToPriceList = Array.from({ length: entryCount }, (_, i) => ({
        name: `tenant=t${i % 3} › application=app${i}`,
        price: (entryCount - i) * 100,
        contextValues: [`t${i % 3}`, `app${i}`],
    }));
    fixture.componentRef.setInput('inputData', {
        costOverview: {
            metricToDistributionMapList: [
                { metric: 'confluent_kafka_server_retained_bytes', nameToPriceList },
            ],
        },
    } as unknown as CostOverviewQuery);
    fixture.componentRef.setInput('lastRequest', {
        totalCents: 100000,
        kafkaStorageCents: 40000,
        kafkaNetworkReadCents: 30000,
        kafkaNetworkWriteCents: 30000,
        contextKeysToGroupBy: ['tenant', 'application'],
    } as CostOverviewRequestInput);

    const options = fixture.componentInstance.sankeyOptions() as {
        series: { data: Node[]; links: unknown[] };
    };
    return { options, height: fixture.componentInstance.chartHeight() };
}

describe('SankeyComponent', () => {
    beforeEach(() => {
        window.matchMedia = ((query: string) => ({
            matches: false,
            media: query,
            addEventListener: () => undefined,
            removeEventListener: () => undefined,
        })) as unknown as typeof window.matchMedia;
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
});
