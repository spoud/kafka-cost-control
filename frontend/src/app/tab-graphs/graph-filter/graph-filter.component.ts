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

        // Fill in what has not been chosen yet, once the options have loaded, so nothing sits
        // waiting on a manual selection.
        //
        // The two controls are not treated alike, because their empty states do not mean the same
        // thing. A metric is the subject of the chart, so there is no such thing as "no metric" -
        // it only ever means "not configured yet", and it is filled wherever it is missing. A
        // group-by is a breakdown, where empty is the meaningful "None (total)", so it is only
        // filled for a host that supplied no filter at all: doing it for a Reporting panel would
        // mean that merely *opening* a panel's settings rewrites and persists a grouping the user
        // never chose.
        effect(() => {
            const metricNames = this.graphFilterService.metricNames();
            const contextKeys = this.graphFilterService.contextKeys();

            if (!this.form.value.metricName && metricNames.length > 0) {
                this.form.patchValue({ metricName: metricNames[0].metricName });
            }

            if (this.existingFilter()) {
                return;
            }
            // `pristine` rather than just an empty value: '' is now what the "None" option sets, so
            // an empty control no longer means "untouched" and defaulting on it alone would
            // overwrite a deliberate choice. patchValue leaves the control pristine, user input
            // does not.
            const groupBy = this.form.get('groupByContext');
            if (groupBy && groupBy.pristine && !groupBy.value && contextKeys.length > 0) {
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
            // Only the metric is required. Group-by is genuinely optional - "None" means the
            // un-broken-down total - and gating on it too meant clearing the key could never be
            // emitted, so the control could be changed but never actually cleared.
            if (filter.metricName) {
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
