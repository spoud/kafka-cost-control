import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { toSignal, toObservable, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { debounceTime, filter, merge, startWith } from 'rxjs';
import { DecimalPipe } from '@angular/common';
import { MatFormField, MatInput, MatLabel, MatPrefix, MatSuffix } from '@angular/material/input';
import { MatSlider, MatSliderThumb } from '@angular/material/slider';
import { MatCard, MatCardContent, MatCardHeader, MatCardTitle } from '@angular/material/card';
import { MatIcon } from '@angular/material/icon';
import { MatChipListbox, MatChipOption } from '@angular/material/chips';
import { CalculateTableGQL, CalculateTableQuery, CostOverviewGQL, CostOverviewQuery } from '../../generated/graphql/sdk';
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
        SankeyComponent,
        MatDateRangeInput,
        MatStartDate,
        MatEndDate,
        MatDatepickerToggle,
        MatDateRangePicker,
        CostTableComponent,
        AbsPipe,
    ],
    templateUrl: './cost.component.html',
    styleUrl: './cost.component.scss',
})
export class CostComponent {
    private calcCostOverview = inject(CostOverviewGQL);
    private calcTable = inject(CalculateTableGQL);
    private fb = inject(FormBuilder);
    graphFilterService = inject(GraphFilterService);

    currentDate = new Date();
    startOfLastMonth = new Date(this.currentDate.getFullYear(), this.currentDate.getMonth() - 1, 1);
    endOfLastMonth = new Date(this.currentDate.getFullYear(), this.currentDate.getMonth(), 0);

    costs = this.fb.group({
        from: [this.startOfLastMonth],
        to: [this.endOfLastMonth],
        kafkaStorage: [0 as number | null],
        kafkaNetworkRead: [0 as number | null],
        kafkaNetworkWrite: [0 as number | null],
        total: [0 as number | null],
    });

    writeWeight = signal(1.0);
    readWeight = signal(2.4);
    groupBy = signal<string[]>([]);

    private costsValue = toSignal(this.costs.valueChanges.pipe(startWith(this.costs.value)));

    inputTotal = computed(() => {
        const v = this.costsValue();
        return (v?.kafkaStorage ?? 0) + (v?.kafkaNetworkRead ?? 0) + (v?.kafkaNetworkWrite ?? 0);
    });

    mismatch = computed(() => {
        const v = this.costsValue();
        const total = v?.total ?? 0;
        return total > 0 && Math.abs(this.inputTotal() - total) > 0.01;
    });

    contextKeysToGroupBy = computed<string[]>(() => {
        const selected = this.groupBy();
        return selected.length > 0 ? selected : this.graphFilterService.contextKeys();
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
    }

    hasResults = computed(
        () => !!this.data() || (this.tableData()?.calculateTable.entries?.length ?? 0) > 0
    );

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
