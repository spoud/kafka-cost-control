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
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { map, startWith } from 'rxjs';
import { SaveBillGQL } from '../../../generated/graphql/sdk';
import { BillEntity } from '../../../generated/graphql/types';
import {
    BILL_LINES,
    billTotal,
    coveredUntilFromLastDay,
    lastDayFromCoveredUntil,
    monthLabel,
    monthLastDay,
    monthOf,
    monthStart,
    recentMonths,
} from '../bill';

export interface BillSaveData {
    /** The bill to edit; without it the dialog adds one. */
    bill?: BillEntity;
    /** Months that already have a bill: saving one of them again replaces it. */
    billedMonths: string[];
}

type Amount = FormControl<number | null>;

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
        ReactiveFormsModule,
    ],
    templateUrl: './bill-save.component.html',
    styleUrl: './bill-save.component.scss',
})
export class BillSaveComponent {
    private saveBill = inject(SaveBillGQL);
    private dialogRef = inject<MatDialogRef<BillSaveComponent>>(MatDialogRef);
    protected data = inject<BillSaveData>(MAT_DIALOG_DATA);

    protected readonly lines = BILL_LINES;
    protected readonly monthLabel = monthLabel;
    protected readonly editing = !!this.data.bill;
    protected readonly months = this.monthChoices();

    protected readonly form = new FormGroup({
        month: new FormControl(
            { value: this.data.bill?.month ?? this.defaultMonth(), disabled: this.editing },
            { nonNullable: true }
        ),
        monthToDate: new FormControl(!!this.data.bill?.coveredUntil, { nonNullable: true }),
        lastDay: new FormControl<Date | null>(
            this.data.bill?.coveredUntil
                ? lastDayFromCoveredUntil(this.data.bill.coveredUntil)
                : null
        ),
        networkWrite: this.amount(this.data.bill?.networkWrite),
        networkRead: this.amount(this.data.bill?.networkRead),
        storage: this.amount(this.data.bill?.storage),
        partitions: this.amount(this.data.bill?.partitions),
        other: this.amount(this.data.bill?.other),
    });

    // the raw value: a disabled month (when editing) is left out of `value`
    private readonly value = toSignal(
        this.form.valueChanges.pipe(
            startWith(null),
            map(() => this.form.getRawValue())
        ),
        { requireSync: true }
    );

    protected readonly total = computed(() =>
        billTotal({
            networkWrite: this.value().networkWrite,
            networkRead: this.value().networkRead,
            storage: this.value().storage,
            partitions: this.value().partitions,
            other: this.value().other,
        })
    );
    protected readonly hasAmount = computed(() =>
        (['networkWrite', 'networkRead', 'storage', 'partitions', 'other'] as const).some(
            key => this.value()[key] != null
        )
    );
    protected readonly negativeLine = computed(() =>
        BILL_LINES.some(line => (this.value()[line.key] ?? 0) < 0)
    );
    protected readonly replacesExisting = computed(
        () => !this.editing && this.data.billedMonths.includes(this.value().month)
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
    }

    protected canSave(): boolean {
        const v = this.value();
        return (
            this.hasAmount() &&
            !this.negativeLine() &&
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
                        networkWrite: v.networkWrite,
                        networkRead: v.networkRead,
                        storage: v.storage,
                        partitions: v.partitions,
                        other: v.other,
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

    private amount(value: number | null | undefined): Amount {
        return new FormControl<number | null>(value ?? null);
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

/** A UTC calendar day as the same local calendar day, for the date picker. */
function localDate(utc: Date): Date {
    return new Date(utc.getUTCFullYear(), utc.getUTCMonth(), utc.getUTCDate());
}
