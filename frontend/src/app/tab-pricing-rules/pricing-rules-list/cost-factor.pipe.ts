import { Pipe, PipeTransform } from '@angular/core';
import { PricingRuleEntity } from '../../../generated/graphql/sdk';

@Pipe({
    name: 'bytesToGb',
})
export class BytesToGbPipe implements PipeTransform {
    transform(entity: PricingRuleEntity): number | null {
        if (entity.metricName.endsWith('bytes')) {
            return Math.round(entity.costFactor * 1024 * 1024 * 1024 * 100000) / 100000;
        }
        return null;
    }
}
