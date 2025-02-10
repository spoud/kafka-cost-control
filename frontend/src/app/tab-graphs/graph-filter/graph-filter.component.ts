import {AfterViewInit, Component, computed, effect, inject, output, resource} from '@angular/core';
import {GraphFilter} from '../tab-graphs.component';
import {FormBuilder, FormGroup, ReactiveFormsModule} from '@angular/forms';
import {toSignal} from '@angular/core/rxjs-interop';
import {debounceTime, firstValueFrom, map} from 'rxjs';
import {MatFormFieldModule} from '@angular/material/form-field';
import {MetricContextKeysGQL, MetricNamesGQL} from '../../../generated/graphql/sdk';
import {MetricNameEntity} from '../../../generated/graphql/types';
import {MatDatepickerModule} from '@angular/material/datepicker';
import {MatSelectModule} from '@angular/material/select';

@Component({
    selector: 'app-graph-filter',
    imports: [ReactiveFormsModule, MatFormFieldModule, MatDatepickerModule, MatSelectModule],
    templateUrl: './graph-filter.component.html',
    styleUrl: './graph-filter.component.scss'
})
export class GraphFilterComponent implements AfterViewInit {
    graphFilter = output<GraphFilter>();

    metricContextKeysGql = inject(MetricContextKeysGQL);
    metricNamesGql = inject(MetricNamesGQL);

    contextKeysResource = resource<string[], never>({
        loader: () => firstValueFrom(this.metricContextKeysGql.fetch().pipe(map(res => res.data?.metricContextKeys)))
    });
    metricNamesResource = resource<MetricNameEntity[], never>({
        loader: () => firstValueFrom(this.metricNamesGql.fetch().pipe(map(res => res.data?.metricNames)))
    });

    contextKeys = computed(() => this.contextKeysResource.value() || []);
    metricNames = computed(() => this.metricNamesResource.value() || []);

    form: FormGroup;

    constructor(formBuilder: FormBuilder) {
        this.form = formBuilder.group({
            from: [new Date(new Date().getTime() - 7 * 24 * 60 * 60 * 1000)],
            to: [new Date()],
            metricName: [''],
            groupByContext: ['']
        });

        const values = toSignal(this.form.valueChanges.pipe(debounceTime(300)));

        effect(() => {
            this.graphFilter.emit(values());
        });

    }

    ngAfterViewInit(): void {
        this.graphFilter.emit(this.form.value);
    }
}
