import {AfterViewInit, Component, effect, inject, input, output} from '@angular/core';
import {GraphFilter} from '../tab-graphs.component';
import {FormBuilder, FormGroup, ReactiveFormsModule} from '@angular/forms';
import {toSignal} from '@angular/core/rxjs-interop';
import {debounceTime} from 'rxjs';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MatDatepickerModule} from '@angular/material/datepicker';
import {MatSelectModule} from '@angular/material/select';
import {GraphFilterService} from './graph-filter.service';

@Component({
    selector: 'app-graph-filter',
    imports: [ReactiveFormsModule, MatFormFieldModule, MatDatepickerModule, MatSelectModule],
    templateUrl: './graph-filter.component.html',
    styleUrl: './graph-filter.component.scss'
})
export class GraphFilterComponent {
    graphFilterService = inject(GraphFilterService);

    existingFilter = input<GraphFilter>()

    graphFilter = output<GraphFilter>();

    form: FormGroup;

    constructor(formBuilder: FormBuilder) {
        this.form = formBuilder.group({
            from: [new Date(new Date().getTime() - 7 * 24 * 60 * 60 * 1000)],
            to: [new Date()],
            metricName: [''],
            groupByContext: ['']
        });

        effect(() => {
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
                    groupByContext: newValues.groupByContext,
                });
            }
        })

        const values = toSignal(this.form.valueChanges.pipe(debounceTime(300)));

        effect(() => {
            this.graphFilter.emit(values());
        });

    }

}
