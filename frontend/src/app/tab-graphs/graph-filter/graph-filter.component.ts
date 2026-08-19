import { Component, computed, effect, inject, input, output, Signal } from '@angular/core';
import { GraphFilter } from '../tab-graphs.component';
import { FormBuilder, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { debounceTime } from 'rxjs';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatSelectModule } from '@angular/material/select';
import { GraphFilterService } from './graph-filter.service';
import {
    DateRange,
    DateRangeQuickSelectComponent,
} from '../../common/date-range-quick-select/date-range-quick-select.component';
import { toContextKeyControl, toContextKeys } from '../../common/context-keys';

@Component({
    selector: 'app-graph-filter',
    imports: [
        ReactiveFormsModule,
        MatFormFieldModule,
        MatDatepickerModule,
        MatSelectModule,
        DateRangeQuickSelectComponent,
    ],
    templateUrl: './graph-filter.component.html',
    styleUrl: './graph-filter.component.scss',
})
export class GraphFilterComponent {
    graphFilterService = inject(GraphFilterService);

    existingFilter = input<GraphFilter>();

    graphFilter = output<GraphFilter>();

    form: FormGroup;
    selectedDateRange: Signal<DateRange | null>;

    constructor() {
        const formBuilder = inject(FormBuilder);

        this.form = formBuilder.group({
            from: [new Date(new Date().getTime() - 7 * 24 * 60 * 60 * 1000)],
            to: [new Date()],
            metricName: [''],
            groupByContext: [''],
        });

        const effectRef = effect(() => {
            const newValues = this.existingFilter();
            if (!newValues) {
                return;
            }
            // we only apply new / incoming filter if it's different from current one
            if (JSON.stringify(this.form.value) !== JSON.stringify(newValues)) {
                this.form.patchValue({
                    from: newValues.from,
                    to: newValues.to,
                    metricName: newValues.metricName,
                    // the control binds one key; the filter carries a list
                    groupByContext: toContextKeyControl(newValues.groupByContext),
                });
            }
            // only do this once
            effectRef.destroy();
        });

        // default metric name / group-by context to the first available option once
        // they've loaded, so the dashboard isn't empty waiting on a manual selection
        effect(() => {
            const metricNames = this.graphFilterService.metricNames();
            const contextKeys = this.graphFilterService.contextKeys();
            if (!this.form.value.metricName && metricNames.length > 0) {
                this.form.patchValue({ metricName: metricNames[0].metricName });
            }
            if (!this.form.value.groupByContext && contextKeys.length > 0) {
                this.form.patchValue({ groupByContext: contextKeys[0] });
            }
        });

        const values = toSignal(this.form.valueChanges.pipe(debounceTime(300)), {
            initialValue: this.form.value,
        });
        this.selectedDateRange = computed(() =>
            values().from && values().to ? { from: values().from, to: values().to } : null
        );
        effect(() => {
            const filter = values();
            if (filter.metricName && filter.groupByContext) {
                // normalized on the way out so consumers never see the control's raw string -
                // writing that onto a Panel is what made configured panels fail their own
                // hydration guard and disappear on the next reload
                this.graphFilter.emit({
                    ...filter,
                    groupByContext: toContextKeys(filter.groupByContext),
                });
            }
        });
    }

    applyDateRange(range: DateRange): void {
        this.form.patchValue({ from: range.from, to: range.to });
    }
}
