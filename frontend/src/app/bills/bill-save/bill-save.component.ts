import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
    MAT_DIALOG_DATA,
    MatDialogActions,
    MatDialogClose,
    MatDialogContent,
    MatDialogRef,
    MatDialogTitle,
} from '@angular/material/dialog';
import { MatButton } from '@angular/material/button';
import {
    MatFormField,
    MatHint,
    MatLabel,
    MatPrefix,
    MatSuffix,
} from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { MatOption, MatSelect } from '@angular/material/select';
import { MatCheckbox } from '@angular/material/checkbox';
import {
    MatDatepicker,
    MatDatepickerInput,
    MatDatepickerToggle,
} from '@angular/material/datepicker';
import { DecimalPipe } from '@angular/common';
import { MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MatTooltip } from '@angular/material/tooltip';
import { MatAutocomplete, MatAutocompleteTrigger } from '@angular/material/autocomplete';
import { GraphFilterService } from '../../tab-graphs/graph-filter/graph-filter.service';
import { FormArray, FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { map, startWith } from 'rxjs';
import { ClustersGQL, SaveBillGQL } from '../../../generated/graphql/sdk';
import { Allocation, BillEntity, BillLineEntity } from '../../../generated/graphql/types';
import {
    ALLOCATIONS,
    billLabel,
    billTotal,
    LineTemplate,
    coveredUntilFromLastDay,
    lastDayFromCoveredUntil,
    monthLabel,
    monthLastDay,
    monthOf,
    monthStart,
    PRESETS,
    recentMonths,
} from '../bill';

export interface BillSaveData {
    /** The bill to edit; without it the dialog adds one. */
    bill?: BillEntity;
    /** The bills there are: saving one for the same month and cluster replaces it. */
    existing: Pick<BillEntity, 'month' | 'cluster'>[];
}

@Component({
    selector: 'app-bill-save',
    imports: [
        MatDialogTitle,
        MatDialogContent,
        MatDialogActions,
        MatDialogClose,
        MatButton,
        MatFormField,
        MatLabel,
        MatHint,
        MatPrefix,
        MatSuffix,
        MatInput,
        MatSelect,
        MatOption,
        MatCheckbox,
        MatDatepicker,
        MatDatepickerInput,
        MatDatepickerToggle,
        DecimalPipe,
        MatIconButton,
        MatIcon,
        MatAutocomplete,
        MatAutocompleteTrigger,
        MatTooltip,
        ReactiveFormsModule,
    ],
    templateUrl: './bill-save.component.html',
    styleUrl: './bill-save.component.scss',
})
export class BillSaveComponent {
    private saveBill = inject(SaveBillGQL);
    private clustersGql = inject(ClustersGQL);
    private graphFilter = inject(GraphFilterService);
    private dialogRef = inject<MatDialogRef<BillSaveComponent>>(MatDialogRef);
    protected data = inject<BillSaveData>(MAT_DIALOG_DATA);

    protected readonly presets = PRESETS;
    protected readonly allocations = ALLOCATIONS;
    protected readonly Allocation = Allocation;
    protected readonly contextKeys = this.graphFilter.contextKeys;
    protected readonly clusters = signal<string[]>([]);
    protected readonly monthLabel = monthLabel;
    protected readonly billLabel = billLabel;
    protected readonly editing = !!this.data.bill;
    protected readonly months = this.monthChoices();

    protected readonly form = new FormGroup({
        month: new FormControl(
            { value: this.data.bill?.month ?? this.defaultMonth(), disabled: this.editing },
            { nonNullable: true }
        ),
        cluster: new FormControl(
            { value: this.data.bill?.cluster ?? '', disabled: this.editing },
            { nonNullable: true }
        ),
        monthToDate: new FormControl(!!this.data.bill?.coveredUntil, { nonNullable: true }),
        lastDay: new FormControl<Date | null>(
            this.data.bill?.coveredUntil
                ? lastDayFromCoveredUntil(this.data.bill.coveredUntil)
                : null
        ),
        lines: new FormArray<LineGroup>((this.data.bill?.lines ?? []).map(line => lineGroup(line))),
    });

    // the raw value: a disabled month (when editing) is left out of `value`
    private readonly value = toSignal(
        this.form.valueChanges.pipe(
            startWith(null),
            map(() => this.form.getRawValue())
        ),
        { requireSync: true }
    );

    protected readonly total = computed(() => billTotal(this.value().lines));
    protected readonly hasAmount = computed(() =>
        this.value().lines.some(line => line.amount != null)
    );
    /** A line without a description, or without what its allocation needs. */
    protected readonly incompleteLine = computed(() =>
        this.value().lines.some(
            line =>
                !line.description.trim() ||
                (line.allocation === Allocation.Metrics
                    ? line.metrics.length === 0
                    : line.amount === null) ||
                (line.allocation === Allocation.Context &&
                    !line.context.some(pair => pair.key.trim() && pair.value.trim()))
        )
    );
    protected readonly negativeLine = computed(() =>
        this.value().lines.some(
            line => line.allocation === Allocation.Metrics && (line.amount ?? 0) < 0
        )
    );
    /** A metric on two lines: it can share only one. */
    protected readonly duplicateMetric = computed(() => {
        const seen = new Set<string>();
        for (const line of this.value().lines) {
            if (line.allocation !== Allocation.Metrics) {
                continue;
            }
            for (const metric of line.metrics) {
                if (seen.has(metric)) {
                    return metric;
                }
                seen.add(metric);
            }
        }
        return null;
    });
    protected readonly cluster = computed(() => this.value().cluster.trim() || null);
    protected readonly replacesExisting = computed(
        () =>
            !this.editing &&
            this.data.existing.some(
                b => b.month === this.value().month && (b.cluster ?? null) === this.cluster()
            )
    );
    /** A month has one bill for all clusters or one per cluster; the other kind is in the way. */
    protected readonly conflict = computed(() => {
        const others = this.data.existing.filter(
            b => b.month === this.value().month && (b.cluster ?? null) !== this.cluster()
        );
        if (this.cluster() === null && others.length) {
            return `${monthLabel(this.value().month)} has bills per cluster: enter this one for a cluster, or delete them first.`;
        }
        if (this.cluster() !== null && others.some(b => !b.cluster)) {
            return `${monthLabel(this.value().month)} has a bill for all clusters: delete it first, or edit it.`;
        }
        return null;
    });
    /** The metrics KCC has collected, and those already on a line (e.g. from a preset). */
    protected readonly metricOptions = computed(() =>
        [
            ...new Set([
                ...this.graphFilter.metricNames().map(m => m.metricName),
                ...this.value().lines.flatMap(line => line.metrics),
            ]),
        ].sort()
    );
    protected readonly firstDay = computed(() => localDate(monthStart(this.value().month)));
    protected readonly lastDayOfMonth = computed(() => localDate(monthLastDay(this.value().month)));
    protected readonly saving = signal(false);
    protected readonly failure = signal<string | null>(null);

    constructor() {
        // the running month's bill is month-to-date: suggest it, up to yesterday
        this.form.controls.month.valueChanges.subscribe(month => {
            if (month === monthOf(new Date()) && !this.form.controls.monthToDate.value) {
                this.form.controls.monthToDate.setValue(true);
            }
            this.clampLastDay();
        });
        this.form.controls.monthToDate.valueChanges.subscribe(on => {
            if (on && !this.form.controls.lastDay.value) {
                const yesterday = new Date();
                yesterday.setDate(yesterday.getDate() - 1);
                this.form.controls.lastDay.setValue(yesterday);
                this.clampLastDay();
            }
        });
        if (!this.editing && this.form.controls.month.value === monthOf(new Date())) {
            this.form.controls.monthToDate.setValue(true);
        }
        this.clustersGql
            .fetch()
            .subscribe(result => this.clusters.set(result.data?.clusters ?? []));
    }

    protected canSave(): boolean {
        const v = this.value();
        return (
            this.hasAmount() &&
            !this.negativeLine() &&
            !this.incompleteLine() &&
            !this.duplicateMetric() &&
            !this.conflict() &&
            !this.saving() &&
            (!v.monthToDate || !!v.lastDay)
        );
    }

    protected save(): void {
        if (!this.canSave()) {
            return;
        }
        const v = this.form.getRawValue();
        this.saving.set(true);
        this.failure.set(null);
        this.saveBill
            .mutate({
                variables: {
                    request: {
                        month: v.month,
                        coveredUntil:
                            v.monthToDate && v.lastDay ? coveredUntilFromLastDay(v.lastDay) : null,
                        cluster: v.cluster.trim() || null,
                        lines: v.lines.map(line => ({
                            description: line.description.trim(),
                            amount: line.amount,
                            allocation: line.allocation,
                            metrics: line.allocation === Allocation.Metrics ? line.metrics : [],
                            context:
                                line.allocation === Allocation.Context
                                    ? line.context
                                          .filter(pair => pair.key.trim() && pair.value.trim())
                                          .map(pair => ({
                                              key: pair.key.trim(),
                                              value: pair.value.trim(),
                                          }))
                                    : [],
                        })),
                    },
                },
            })
            .subscribe({
                next: result => {
                    this.saving.set(false);
                    if (result.error || !result.data) {
                        this.failure.set(result.error?.message ?? 'The bill could not be saved.');
                        return;
                    }
                    this.dialogRef.close(result.data.saveBill);
                },
                error: err => {
                    this.saving.set(false);
                    this.failure.set(err.message);
                },
            });
    }

    protected get lines(): FormArray<LineGroup> {
        return this.form.controls.lines;
    }

    /** Replaces the lines with the preset's, amounts left to type. */
    protected usePreset(lines: readonly LineTemplate[]): void {
        this.lines.clear();
        lines.forEach(line => this.lines.push(lineGroup(line)));
    }

    protected addLine(): void {
        this.lines.push(lineGroup());
    }

    protected removeLine(index: number): void {
        this.lines.removeAt(index);
    }

    protected addContextPair(line: LineGroup): void {
        line.controls.context.push(contextPair());
    }

    protected removeContextPair(line: LineGroup, index: number): void {
        line.controls.context.removeAt(index);
    }

    /** The month before the current one: the bill most often entered. */
    private defaultMonth(): string {
        return this.months[1] ?? this.months[0];
    }

    /** The last two years, and the month being edited even if it is older. */
    private monthChoices(): string[] {
        const months = recentMonths(new Date(), 24);
        const editing = this.data.bill?.month;
        return editing && !months.includes(editing) ? [...months, editing] : months;
    }

    private clampLastDay(): void {
        const day = this.form.controls.lastDay.value;
        if (!day) {
            return;
        }
        if (day < this.firstDay()) {
            this.form.controls.lastDay.setValue(this.firstDay());
        } else if (day > this.lastDayOfMonth()) {
            this.form.controls.lastDay.setValue(this.lastDayOfMonth());
        }
    }
}

function contextPair(key = '', value = '') {
    return new FormGroup({
        key: new FormControl(key, { nonNullable: true }),
        value: new FormControl(value, { nonNullable: true }),
    });
}

function lineGroup(line?: Partial<BillLineEntity> | LineTemplate) {
    const context = line && 'context' in line ? line.context : null;
    const pairs = context?.length ? context : [{ key: '', value: '' }];
    return new FormGroup({
        description: new FormControl(line?.description ?? '', { nonNullable: true }),
        amount: new FormControl<number | null>(
            line && 'amount' in line ? (line.amount ?? null) : null
        ),
        allocation: new FormControl(line?.allocation ?? Allocation.Metrics, { nonNullable: true }),
        metrics: new FormControl<string[]>([...(line?.metrics ?? [])], { nonNullable: true }),
        context: new FormArray(pairs.map(pair => contextPair(pair.key ?? '', pair.value ?? ''))),
    });
}

type LineGroup = ReturnType<typeof lineGroup>;

/** A UTC calendar day as the same local calendar day, for the date picker. */
function localDate(utc: Date): Date {
    return new Date(utc.getUTCFullYear(), utc.getUTCMonth(), utc.getUTCDate());
}
