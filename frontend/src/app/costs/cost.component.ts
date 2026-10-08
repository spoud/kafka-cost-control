import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { toSignal, toObservable, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { debounceTime, filter, merge, startWith } from 'rxjs';
import { DecimalPipe } from '@angular/common';
import { MatFormField, MatLabel, MatSuffix } from '@angular/material/input';
import { MatCard, MatCardContent, MatCardHeader, MatCardTitle } from '@angular/material/card';
import { MatIcon } from '@angular/material/icon';
import { MatChipListbox, MatChipOption } from '@angular/material/chips';
import { MatButton, MatIconButton } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSelectModule } from '@angular/material/select';
import { MatProgressSpinner } from '@angular/material/progress-spinner';
import { RouterLink } from '@angular/router';
import {
    CdkDrag,
    CdkDragDrop,
    CdkDragHandle,
    CdkDropList,
    moveItemInArray,
} from '@angular/cdk/drag-drop';
import { BilledCostsGQL, BilledCostsQuery } from '../../generated/graphql/sdk';
import { BilledCostRequestInput } from '../../generated/graphql/types';
import { SankeyComponent } from './sankey/sankey.component';
import {
    MatDatepickerToggle,
    MatDateRangeInput,
    MatDateRangePicker,
    MatEndDate,
    MatStartDate,
} from '@angular/material/datepicker';
import { CostRow, CostTableComponent } from './cost-table/cost-table.component';
import { GraphFilterService } from '../tab-graphs/graph-filter/graph-filter.service';
import { PageHeaderComponent } from '../common/page-header/page-header.component';
import { DateRangeQuickSelectComponent } from '../common/date-range-quick-select/date-range-quick-select.component';
import { DateRange, utcDayRange } from '../common/date-range';
import { CostOverviewFormValues, CostOverviewStore } from './store/cost-overview.store';
import { SaveConfigDialogComponent } from './save-config-dialog/save-config-dialog.component';
import { EmptyStateComponent } from '../common/empty-state/empty-state.component';
import { UnpricedMetricsService } from '../common/unpriced-metrics';
import { lastDayFromCoveredUntil, monthLabel } from '../bills/bill';

type BilledCosts = BilledCostsQuery['billedCosts'];

/** How each month of the range is costed, for the list on the page. */
export interface MonthStatus {
    label: string;
    billed: boolean;
    /** For a month-to-date bill: the last day it covers. */
    billedUpTo: Date | null;
}

export function monthStatuses(costs: BilledCosts): MonthStatus[] {
    return costs.months.map(month => {
        const monthEnd = new Date(String(month.to)).getTime();
        const partial =
            month.billed &&
            !!month.billedUntil &&
            new Date(String(month.billedUntil)).getTime() < monthEnd;
        return {
            label: monthLabel(month.month),
            billed: month.billed,
            billedUpTo: partial ? lastDayFromCoveredUntil(month.billedUntil) : null,
        };
    });
}

/** Table rows in dollars: each group's cost per metric, and its share of the metric. */
export function costRows(costs: BilledCosts): CostRow[] {
    return costs.metrics.flatMap(metric => {
        const metricCents = metric.shares.reduce((sum, share) => sum + share.price, 0);
        return metric.shares.map(share => ({
            metric: metric.metric,
            context: share.contextValues,
            total: share.price / 100,
            estimated: share.estimatedPrice / 100,
            percentage: metricCents ? share.price / metricCents : 0,
        }));
    });
}

@Component({
    imports: [
        ReactiveFormsModule,
        DecimalPipe,
        MatFormField,
        MatLabel,
        MatSuffix,
        MatCard,
        MatCardContent,
        MatCardHeader,
        MatCardTitle,
        MatIcon,
        MatChipListbox,
        MatChipOption,
        MatButton,
        MatIconButton,
        MatSelectModule,
        MatProgressSpinner,
        SankeyComponent,
        EmptyStateComponent,
        MatDateRangeInput,
        MatStartDate,
        MatEndDate,
        MatDatepickerToggle,
        MatDateRangePicker,
        CostTableComponent,
        PageHeaderComponent,
        DateRangeQuickSelectComponent,
        CdkDropList,
        CdkDrag,
        CdkDragHandle,
        RouterLink,
    ],
    templateUrl: './cost.component.html',
    styleUrl: './cost.component.scss',
})
export class CostComponent {
    private billedCosts = inject(BilledCostsGQL);
    private fb = inject(FormBuilder);
    private _store = inject(CostOverviewStore);
    private _dialog = inject(MatDialog);
    graphFilterService = inject(GraphFilterService);
    unpriced = inject(UnpricedMetricsService);

    currentDate = new Date();
    startOfLastMonth = new Date(this.currentDate.getFullYear(), this.currentDate.getMonth() - 1, 1);
    endOfLastMonth = new Date(this.currentDate.getFullYear(), this.currentDate.getMonth(), 0);

    private restored = this._store.current();

    costs = this.fb.group({
        from: [this.restored?.from ?? this.startOfLastMonth],
        to: [this.restored?.to ?? this.endOfLastMonth],
    });

    groupBy = signal<string[]>(this.restored?.groupBy ?? []);

    savedConfigs = this._store.entities;
    selectedConfigId = signal<string | null>(null);
    selectedConfigName = computed(
        () => this.savedConfigs().find(c => c.id === this.selectedConfigId())?.name ?? null
    );

    private costsValue = toSignal(this.costs.valueChanges.pipe(startWith(this.costs.value)));

    selectedDateRange = computed<DateRange | null>(() => {
        const v = this.costsValue();
        return v?.from && v?.to ? { from: v.from, to: v.to } : null;
    });

    // context keys not yet in the group-by order, offered as "click to add" chips
    availableContextKeys = computed<string[]>(() =>
        this.graphFilterService.contextKeys().filter(key => !this.groupBy().includes(key))
    );

    currentFormValues = computed<CostOverviewFormValues>(() => {
        const v = this.costsValue();
        return {
            from: v?.from ?? this.startOfLastMonth,
            to: v?.to ?? this.endOfLastMonth,
            groupBy: this.groupBy(),
        };
    });

    data = signal<BilledCosts | undefined>(undefined);
    /** The grouping the shown costs were computed with. */
    shownGroupBy = signal<string[]>([]);
    loading = signal(false);
    error = signal<string | null>(null);

    rows = computed(() => (this.data() ? costRows(this.data()!) : []));
    months = computed(() => (this.data() ? monthStatuses(this.data()!) : []));
    totalCost = computed(() => this.rows().reduce((sum, row) => sum + row.total, 0));
    estimatedCost = computed(() => this.rows().reduce((sum, row) => sum + row.estimated, 0));
    anyEstimated = computed(() => this.months().some(m => !m.billed || m.billedUpTo));
    noCosts = computed(() => !!this.data() && this.rows().length === 0);

    constructor() {
        this.unpriced.reload();
        merge(this.costs.valueChanges, toObservable(this.groupBy))
            .pipe(
                debounceTime(600),
                filter(() => this.canCalculate()),
                takeUntilDestroyed()
            )
            .subscribe(() => this.calculate());

        effect(() => {
            this._store.setCurrent(this.currentFormValues());
        });

        if (this.canCalculate()) {
            this.calculate();
        }
    }

    /** A date the picker can't parse arrives as null, and the backend rejects a missing start. */
    private canCalculate(): boolean {
        const { from, to } = this.costs.value;
        return isValidDate(from) && isValidDate(to);
    }

    applyDateRange(range: DateRange): void {
        this.costs.patchValue({ from: range.from, to: range.to });
    }

    addGroupByKey(key: string): void {
        if (!this.groupBy().includes(key)) {
            this.groupBy.set([...this.groupBy(), key]);
        }
    }

    removeGroupByKey(key: string): void {
        this.groupBy.set(this.groupBy().filter(k => k !== key));
    }

    dropGroupByKey(event: CdkDragDrop<string[]>): void {
        const reordered = [...this.groupBy()];
        moveItemInArray(reordered, event.previousIndex, event.currentIndex);
        this.groupBy.set(reordered);
    }

    openSaveDialog(): void {
        const dialogRef = this._dialog.open(SaveConfigDialogComponent);
        dialogRef.afterClosed().subscribe((name: string | undefined) => {
            if (name) {
                const id = this._store.saveConfig(name, this.currentFormValues());
                this.selectedConfigId.set(id);
            }
        });
    }

    updateSelectedConfig(): void {
        const id = this.selectedConfigId();
        if (!id) {
            return;
        }
        this._store.updateConfig(id, this.currentFormValues());
    }

    loadConfig(id: string): void {
        const config = this.savedConfigs().find(c => c.id === id);
        if (!config) {
            return;
        }
        this.selectedConfigId.set(id);
        this.costs.patchValue({ from: config.from, to: config.to });
        this.groupBy.set(config.groupBy);
    }

    deleteConfig(id: string): void {
        this._store.deleteConfig(id);
        if (this.selectedConfigId() === id) {
            this.selectedConfigId.set(null);
        }
    }

    calculate() {
        const groupBy = this.groupBy();
        // whole UTC days, like the bills' months
        const range = utcDayRange({ from: this.costs.value.from!, to: this.costs.value.to! });
        const request: BilledCostRequestInput = {
            from: range.from,
            to: range.to,
            contextKeysToGroupBy: groupBy,
        };
        this.loading.set(true);
        this.billedCosts.fetch({ variables: { request } }).subscribe({
            next: response => {
                this.loading.set(false);
                if (response.error || !response.data) {
                    this.error.set(response.error?.message ?? 'The costs could not be loaded.');
                    return;
                }
                this.error.set(null);
                this.shownGroupBy.set(groupBy);
                this.data.set(response.data.billedCosts);
            },
            error: err => {
                this.loading.set(false);
                this.error.set(err.message);
            },
        });
    }
}

function isValidDate(value: Date | null | undefined): value is Date {
    return value instanceof Date && !isNaN(value.getTime());
}
