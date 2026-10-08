import { PriceUnit, PricingRuleEntity } from '../../../generated/graphql/types';
import { priceHistory } from './pricing-rules-list.component';

const GB = 1024 * 1024 * 1024;

describe('priceHistory', () => {
    it("ends each earlier price on the day before the next one's start", () => {
        const rule = {
            metricName: 'confluent_kafka_server_request_bytes',
            baseCost: 0,
            costFactor: 0.16 / GB,
            price: 0.16,
            priceUnit: PriceUnit.Gb,
            creationTime: '2026-11-01T00:00:00Z',
            validFrom: '2026-11-01T00:00:00Z',
            earlierPrices: [
                {
                    baseCost: 0,
                    costFactor: 0.12 / GB,
                    price: 0.12,
                    priceUnit: PriceUnit.Gb,
                    validUntil: '2026-10-01T00:00:00Z',
                },
                {
                    baseCost: 0,
                    costFactor: 0.1495 / GB,
                    price: 0.1495,
                    priceUnit: PriceUnit.Gb,
                    validFrom: '2026-10-01T00:00:00Z',
                    validUntil: '2026-11-01T00:00:00Z',
                },
            ],
        } as PricingRuleEntity;
        const utc = (iso: string) =>
            new Date(iso).toLocaleDateString(undefined, { timeZone: 'UTC' });

        const lines = priceHistory(rule).split('\n');

        expect(lines[0]).toMatch(new RegExp(`^always – ${utc('2026-09-30')}: `));
        expect(lines[1]).toMatch(new RegExp(`^${utc('2026-10-01')} – ${utc('2026-10-31')}: `));
    });
});
