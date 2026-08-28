import { Component, computed, effect, inject, signal } from '@angular/core';
import { GraphFilterComponent } from './graph-filter/graph-filter.component';
import { GraphPanelComponent } from './graph-panel/graph-panel.component';
import { GraphFilterService } from './graph-filter/graph-filter.service';
import { MatIcon } from '@angular/material/icon';
import { MatAnchor } from '@angular/material/button';
import { PageHeaderComponent } from '../common/page-header/page-header.component';
import { FilterBarComponent } from '../common/filter-bar/filter-bar.component';
import { EmptyStateComponent } from '../common/empty-state/empty-state.component';
import {
    isRevivableDate,
    readPersisted,
    reviveDate,
    writePersisted,
} from '../common/persisted-state';
import { toContextKeys } from '../common/context-keys';

export interface GraphFilter {
    from: Date;
    to?: Date;
    metricName?: string;
    groupByContext: string[];
}

const FILTER_KEY = 'kcc_explore_filter';
/** Bump when GraphFilter changes in a way values written by an older build cannot satisfy. */
const PERSISTED_VERSION = 1;

function isGraphFilter(value: unknown): value is GraphFilter {
    if (!value || typeof value !== 'object') {
        return false;
    }
    const candidate = value as Partial<GraphFilter>;
    return (
        isRevivableDate(candidate.from) &&
        (candidate.to === undefined || isRevivableDate(candidate.to)) &&
        (candidate.metricName === undefined || typeof candidate.metricName === 'string') &&
        candidate.groupByContext !== undefined
    );
}

function reviveFilter(filter: GraphFilter): GraphFilter {
    return {
        ...filter,
        from: reviveDate(filter.from),
        to: filter.to === undefined ? undefined : reviveDate(filter.to),
        // filters stored before the normalization hold a bare string here
        groupByContext: toContextKeys(filter.groupByContext),
    };
}

/**
 * Revived exactly once, on the way out of storage: the raw value has ISO strings where the type
 * says Date, and handing that to the filter form puts strings back on the form controls, which
 * then reach csvDownloadUrl and the quick-select's isSameDay as ".toISOString is not a function".
 */
function restoreFilter(): GraphFilter | undefined {
    const stored = readPersisted(FILTER_KEY, PERSISTED_VERSION, isGraphFilter);
    return stored ? reviveFilter(stored) : undefined;
}

@Component({
    selector: 'app-tab-graphs',
    imports: [
        GraphFilterComponent,
        GraphPanelComponent,
        MatIcon,
        MatAnchor,
        PageHeaderComponent,
        FilterBarComponent,
        EmptyStateComponent,
    ],
    templateUrl: './tab-graphs.component.html',
    styleUrl: './tab-graphs.component.scss',
})
export class TabGraphsComponent {
    graphFilterService = inject(GraphFilterService);

    /**
     * Restored from the previous visit so a reload does not silently reset the range back to
     * "last 7 days" — losing a range you had just dialled in is the kind of small thing that
     * makes a dashboard feel disposable.
     */
    protected readonly restoredFilter = restoreFilter();

    filter = signal<GraphFilter | undefined>(this.restoredFilter);

    /** The window the charts should span, whether or not there is data at both ends of it. */
    selectedRange = computed(() => {
        const filter = this.filter();
        return filter ? { from: filter.from, to: filter.to ?? new Date() } : null;
    });

    csvDownloadUrl = computed(() => {
        const from =
            this.filter()?.from.toISOString() ??
            new Date(new Date().getTime() - 7 * 24 * 60 * 60 * 1000).toISOString();
        const to = this.filter()?.to?.toISOString() ?? new Date().toISOString();
        const groupBy = this.filter()?.groupByContext?.join(',') ?? '';
        const params = new URLSearchParams({
            fromDate: from,
            toDate: to,
            groupByContextKey: groupBy,
        });
        return `olap/export/aggregated?${params}`;
    });

    historyData = this.graphFilterService.historyResource(this.filter);

    constructor() {
        effect(() => {
            const filter = this.filter();
            if (filter) {
                writePersisted(FILTER_KEY, PERSISTED_VERSION, filter);
            }
        });
    }
}
