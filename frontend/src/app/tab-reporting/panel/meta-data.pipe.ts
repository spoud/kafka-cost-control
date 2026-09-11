import { inject, Pipe, PipeTransform } from '@angular/core';
import { Panel } from '../panel.type';
import { IntlDateService } from '../../services/intl-date.service';

/**
 * The one-line summary of what a panel is actually showing: metric, window, grouping. Exported as
 * a function too, because the PDF export needs the same string and a pipe is awkward to call from
 * a component.
 */
export function formatPanelMeta(panel: Panel, dates: IntlDateService): string {
    const strings = [
        `(`,
        panel.metricName ? `${panel.metricName}, ` : null,
        dates.transform(panel.from),
        panel.to ? ` - ${dates.transform(panel.to)}` : ` - now`,
        panel.groupByContext.length > 0 ? ` and grouped by ${panel.groupByContext}` : null,
        `)`,
    ];
    return strings.join('');
}

@Pipe({
    name: 'metaData',
})
export class MetaDataPipe implements PipeTransform {
    intlDateService = inject(IntlDateService);

    transform(panel: Panel): string | null {
        if (!panel) {
            return null;
        }
        return formatPanelMeta(panel, this.intlDateService);
    }
}
