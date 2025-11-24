import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatFormField, MatInput, MatLabel, MatPrefix, MatSuffix } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButton } from '@angular/material/button';
import {
    CalculateTableGQL,
    CalculateTableQuery,
    CalculateTopDownGQL,
    CalculateTopDownQuery,
    CalculateTopDownRequestInput,
} from '../../generated/graphql/sdk';
import { SankeyComponent } from './sankey/sankey.component';
import {
    MatDatepickerToggle,
    MatDateRangeInput,
    MatDateRangePicker,
    MatEndDate,
    MatStartDate,
} from '@angular/material/datepicker';
import {
    MatStep,
    MatStepLabel,
    MatStepper,
    MatStepperNext,
    MatStepperPrevious,
} from '@angular/material/stepper';
import { GraphFilterService } from '../tab-graphs/graph-filter/graph-filter.service';
import { CostTableComponent } from './cost-table/cost-table.component';

@Component({
    imports: [
        ReactiveFormsModule,
        MatFormField,
        MatInput,
        MatLabel,
        MatFormField,
        MatButton,
        SankeyComponent,
        MatDateRangeInput,
        MatStartDate,
        MatEndDate,
        MatDatepickerToggle,
        MatSuffix,
        MatDateRangePicker,
        MatPrefix,
        MatStepper,
        MatStep,
        MatStepLabel,
        MatStepperNext,
        MatSelectModule,
        MatStepperPrevious,
        CostTableComponent,
    ],
    templateUrl: './cost.component.html',
    styleUrl: './cost.component.scss',
})
export class CostComponent {
    private calcTopDown = inject(CalculateTopDownGQL);
    private calcTable = inject(CalculateTableGQL);
    private fb = inject(FormBuilder);

    currentDate = new Date();
    startOfLastMonth = new Date(this.currentDate.getFullYear(), this.currentDate.getMonth() - 1, 1);
    endOfLastMonth = new Date(this.currentDate.getFullYear(), this.currentDate.getMonth(), 0);

    costs = this.fb.group({
        from: [this.startOfLastMonth],
        to: [this.endOfLastMonth],
        kafkaStorage: [],
        kafkaNetworkRead: [],
        kafkaNetworkWrite: [],
        total: [],
    });
    contextLabels = this.fb.group({});

    data = signal<CalculateTopDownQuery | undefined>(undefined);
    tableData = signal<CalculateTableQuery>({ calculateTable: {} });
    lastRequest = signal<CalculateTopDownRequestInput | undefined>(undefined);

    calculate() {
        console.log(this.costs.value);
        const request: CalculateTopDownRequestInput = {
            from: this.costs.value.from,
            to: this.costs.value.to,
            kafkaStorageCents: (this.costs.value.kafkaStorage ?? 0) * 100,
            kafkaNetworkReadCents: (this.costs.value.kafkaNetworkRead ?? 0) * 100,
            kafkaNetworkWriteCents: (this.costs.value.kafkaNetworkWrite ?? 0) * 100,
            totalCents: (this.costs.value.total ?? 0) * 100,
            contextKeysToGroupBy: ['application', 'stage'], // Todo :)
        };
        console.log(request);
        this.calcTopDown
            .fetch({
                request: request,
            })
            .subscribe(response => {
                this.lastRequest.set(request);
                this.data.set(response.data);
                console.log(response);
            });

        this.calcTable.fetch({ request }).subscribe(response => {
            this.tableData.set(response.data);
        });
    }
}
