import { Pipe, PipeTransform } from '@angular/core';
import { PricingRuleEntity } from '../../../generated/graphql/types';

const BYTES_PER_GB = 1024 * 1024 * 1024;

/** The cost factor per GB (2^30 bytes, as Confluent bills) for byte metrics, otherwise null. */
export function costFactorPerGb(metricName: string, costFactor: number): number | null {
    if (metricName.endsWith('bytes')) {
        return Math.round(costFactor * BYTES_PER_GB * 100000) / 100000;
    }
    return null;
}

/** The cost factor for a price per GB, the inverse of {@link costFactorPerGb}. */
export function costFactorFromPerGb(pricePerGb: number): number {
    return pricePerGb / BYTES_PER_GB;
}

@Pipe({
    name: 'bytesToGb',
})
export class BytesToGbPipe implements PipeTransform {
    transform(entity: PricingRuleEntity): number | null {
        return costFactorPerGb(entity.metricName, entity.costFactor);
    }
}
