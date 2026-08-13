import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { toSignal, toObservable, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { debounceTime, filter, merge, startWith } from 'rxjs';
import { DecimalPipe } from '@angular/common';
import { MatFormField, MatInput, MatLabel, MatPrefix, MatSuffix } from '@angular/material/input';
import { MatSlider, MatSliderThumb } from '@angular/material/slider';
import { MatCard, MatCardContent, MatCardHeader, MatCardTitle } from '@angular/material/card';
import { MatIcon } from '@angular/material/icon';
import { MatChipListbox, MatChipOption } from '@angular/material/chips';
import { MatButton, MatIconButton } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatSelectModule } from '@angular/material/select';
import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList, moveItemInArray } from '@angular/cdk/drag-drop';
import {
    CalculateTableGQL,
    CalculateTableQuery,
    CostOverviewGQL,
    CostOverviewQuery,
} from '../../generated/graphql/sdk';
import { CostOverviewRequestInput } from '../../generated/graphql/types';
import { SankeyComponent } from './sankey/sankey.component';
import {
    MatDatepickerToggle,
    MatDateRangeInput,
    MatDateRangePicker,
    MatEndDate,
    MatStartDate,
} from '@angular/material/datepicker';
import { CostTableComponent } from './cost-table/cost-table.component';
import { AbsPipe } from '../common/abs.pipe';
import { GraphFilterService } from '../tab-graphs/graph-filter/graph-filter.service';
import { PageHeaderComponent } from '../common/page-header/page-header.component';
import {
    DateRange,
    DateRangeQuickSelectComponent,
} from '../common/date-range-quick-select/date-range-quick-select.component';
import { CostOverviewFormValues, CostOverviewStore } from './store/cost-overview.store';
import { SaveConfigDialogComponent } from './save-config-dialog/save-config-dialog.component';

@Component({
    imports: [
        ReactiveFormsModule,
        DecimalPipe,
        MatFormField,
        MatInput,
        MatLabel,
        MatPrefix,
        MatSuffix,
        MatSlider,
        MatSliderThumb,
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
        SankeyComponent,
        MatDateRangeInput,
        MatStartDate,
        MatEndDate,
        MatDatepickerToggle,
        MatDateRangePicker,
        CostTableComponent,
        AbsPipe,
        PageHeaderComponent,
        DateRangeQuickSelectComponent,
        CdkDropList,
        CdkDrag,
        CdkDragHandle,
    ],
    templateUrl: './cost.component.html',
    styleUrl: './cost.component.scss',
})
export class CostComponent {
    private calcCostOverview = inject(CostOverviewGQL);
    private calcTable = inject(CalculateTableGQL);
    private fb = inject(FormBuilder);
    private _store = inject(CostOverviewStore);
    private _dialog = inject(MatDialog);
    graphFilterService = inject(GraphFilterService);

    currentDate = new Date();
    startOfLastMonth = new Date(this.currentDate.getFullYear(), this.currentDate.getMonth() - 1, 1);
    endOfLastMonth = new Date(this.currentDate.getFullYear(), this.currentDate.getMonth(), 0);

    private restored = this._store.current();

    costs = this.fb.group({
        from: [this.restored?.from ?? this.startOfLastMonth],
        to: [this.restored?.to ?? this.endOfLastMonth],
        kafkaStorage: [this.restored?.kafkaStorage ?? (0 as number | null)],
        kafkaNetworkRead: [this.restored?.kafkaNetworkRead ?? (0 as number | null)],
        kafkaNetworkWrite: [this.restored?.kafkaNetworkWrite ?? (0 as number | null)],
        total: [this.restored?.total ?? (0 as number | null)],
    });

    writeWeight = signal(1.0);
    readWeight = signal(2.4);
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

    inputTotal = computed(() => {
        const v = this.costsValue();
        return (v?.kafkaStorage ?? 0) + (v?.kafkaNetworkRead ?? 0) + (v?.kafkaNetworkWrite ?? 0);
    });

    mismatch = computed(() => {
        const v = this.costsValue();
        const total = v?.total ?? 0;
        return total > 0 && Math.abs(this.inputTotal() - total) > 0.01;
    });

    contextKeysToGroupBy = computed<string[]>(() => this.groupBy());

    // context keys not yet in the group-by order, offered as "click to add" chips
    availableContextKeys = computed<string[]>(() =>
        this.graphFilterService.contextKeys().filter(key => !this.groupBy().includes(key))
    );

    currentFormValues = computed<CostOverviewFormValues>(() => {
        const v = this.costsValue();
        return {
            from: v?.from ?? this.startOfLastMonth,
            to: v?.to ?? this.endOfLastMonth,
            kafkaStorage: v?.kafkaStorage ?? null,
            kafkaNetworkRead: v?.kafkaNetworkRead ?? null,
            kafkaNetworkWrite: v?.kafkaNetworkWrite ?? null,
            total: v?.total ?? null,
            groupBy: this.groupBy(),
        };
    });

    data = signal<CostOverviewQuery | undefined>(undefined);
    tableData = signal<CalculateTableQuery>({ calculateTable: { entries: null } });
    lastRequest = signal<CostOverviewRequestInput | undefined>(undefined);

    constructor() {
        merge(this.costs.valueChanges, toObservable(this.groupBy))
            .pipe(
                debounceTime(600),
                filter(() => (this.costs.value.total ?? 0) > 0),
                takeUntilDestroyed()
            )
            .subscribe(() => this.calculate());

        effect(() => {
            this._store.setCurrent(this.currentFormValues());
        });

        // restore previous results immediately if we brought back a usable configuration
        if ((this.restored?.total ?? 0) > 0) {
            this.calculate();
        }
    }

    hasResults = computed(
        () => !!this.data() || (this.tableData()?.calculateTable.entries?.length ?? 0) > 0
    );

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
        this.costs.patchValue({
            from: config.from,
            to: config.to,
            kafkaStorage: config.kafkaStorage,
            kafkaNetworkRead: config.kafkaNetworkRead,
            kafkaNetworkWrite: config.kafkaNetworkWrite,
            total: config.total,
        });
        this.groupBy.set(config.groupBy);
    }

    deleteConfig(id: string): void {
        this._store.deleteConfig(id);
        if (this.selectedConfigId() === id) {
            this.selectedConfigId.set(null);
        }
    }

    calculate() {
        const request: CostOverviewRequestInput = {
            from: this.costs.value.from,
            to: this.costs.value.to,
            kafkaStorageCents: (this.costs.value.kafkaStorage ?? 0) * 100,
            kafkaNetworkReadCents: (this.costs.value.kafkaNetworkRead ?? 0) * 100,
            kafkaNetworkWriteCents: (this.costs.value.kafkaNetworkWrite ?? 0) * 100,
            totalCents: (this.costs.value.total ?? 0) * 100,
            contextKeysToGroupBy: this.contextKeysToGroupBy(),
        };
        this.calcCostOverview.fetch({ variables: { request } }).subscribe(response => {
            this.lastRequest.set(request);
            this.data.set(response.data);
        });
        this.calcTable.fetch({ variables: { request } }).subscribe(response => {
            this.tableData.set(response.data!);
        });
    }
}
