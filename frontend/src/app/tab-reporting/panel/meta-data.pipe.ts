import { inject, Pipe, PipeTransform } from '@angular/core';
import { Panel } from '../panel.type';
import { IntlDateService } from '../../services/intl-date.service';

@Pipe({
    name: 'metaData',
})
export class MetaDataPipe implements PipeTransform {
    intlDateService = inject(IntlDateService);

    transform(panel: Panel): string | null {
        if (!panel) {
            return null;
        }
        const strings = [
            `(`,
            panel.metricName ? `${panel.metricName}, ` : null,
            this.intlDateService.transform(panel.from),
            panel.to ? ` - ${this.intlDateService.transform(panel.to)}` : ` - now`,
            panel.groupByContext.length > 0 ? ` and grouped by ${panel.groupByContext}` : null,
            `)`,
        ];
        return strings.join('');
    }
}
