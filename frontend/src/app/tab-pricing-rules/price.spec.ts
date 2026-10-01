import { PriceUnit } from '../../generated/graphql/types';
import { costFactorOf, defaultPriceUnit, formatPrice, priceFromCostFactor } from './price';

const GB = 2 ** 30;

describe('price units', () => {
    it('guesses the usual unit from the metric name', () => {
        expect(defaultPriceUnit('confluent_kafka_server_retained_bytes')).toBe(PriceUnit.GbHour);
        expect(defaultPriceUnit('confluent_kafka_server_request_bytes')).toBe(PriceUnit.Gb);
        expect(defaultPriceUnit('kafka_topic_partition_count')).toBe(PriceUnit.Unit);
    });

    it('derives the cost factor the way the aggregator does', () => {
        expect(costFactorOf(0.1495, PriceUnit.Gb)).toBeCloseTo(0.1495 / GB, 20);
        expect(costFactorOf(0.00012603, PriceUnit.GbHour, 3)).toBeCloseTo(
            (3 * 0.00012603) / GB,
            24
        );
        expect(costFactorOf(0.0046, PriceUnit.Unit)).toBe(0.0046);
        expect(priceFromCostFactor(costFactorOf(0.1495, PriceUnit.Gb), PriceUnit.Gb)).toBeCloseTo(
            0.1495,
            12
        );
    });

    it('formats a price as entered, multiplier included', () => {
        expect(
            formatPrice({
                metricName: 'confluent_kafka_server_retained_bytes',
                costFactor: 3.52e-13,
                price: 0.00012603,
                priceUnit: PriceUnit.GbHour,
                multiplier: 3,
                multiplierLabel: 'replicas',
            })
        ).toBe('$0.00012603 per GB-hour × 3 replicas');
        expect(
            formatPrice({
                metricName: 'x_bytes',
                costFactor: 1,
                price: 0.1495,
                priceUnit: PriceUnit.Gb,
            })
        ).toBe('$0.1495 per GB');
    });

    it('shows a rule saved with only a cost factor in its metric’s usual unit', () => {
        expect(
            formatPrice({
                metricName: 'confluent_kafka_server_request_bytes',
                costFactor: 0.1495 / GB,
            })
        ).toBe('$0.1495 per GB');
        expect(formatPrice({ metricName: 'kafka_topic_partition_count', costFactor: 0.0046 })).toBe(
            '$0.0046 per unit'
        );
    });
});
