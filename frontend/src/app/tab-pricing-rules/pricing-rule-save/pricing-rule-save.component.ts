import { Component, computed, inject } from '@angular/core';
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
import { MatError, MatFormField, MatHint, MatLabel, MatPrefix } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { MatSelect } from '@angular/material/select';
import { MatRadioButton, MatRadioGroup } from '@angular/material/radio';
import {
    MatDatepicker,
    MatDatepickerInput,
    MatDatepickerToggle,
} from '@angular/material/datepicker';
import { MatSuffix } from '@angular/material/form-field';
import { DatePipe } from '@angular/common';
import { MatAutocomplete, MatAutocompleteTrigger, MatOption } from '@angular/material/autocomplete';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatSnackBar } from '@angular/material/snack-bar';
import { startWith } from 'rxjs';
import { SavePricingRuleGQL } from '../../../generated/graphql/sdk';
import { PriceUnit, PricingRuleEntity } from '../../../generated/graphql/types';
import { costFactorOf, defaultPriceUnit, PRICE_UNITS, priceFromCostFactor } from '../price';

export interface PricingRuleSaveData {
    /** The rule to edit; without it the dialog creates one. */
    rule?: PricingRuleEntity;
    /** Metric name to start a new rule with, e.g. from the unpriced-metrics list. */
    metricName?: string;
    /** Collected metric names, offered as suggestions. */
    metricNames: string[];
    /** Metrics that already have a rule; saving one of them again replaces it. */
    pricedMetricNames: string[];
}

/** Midnight UTC at the start of the picked calendar day. */
export function utcDayStart(day: Date): string {
    return new Date(Date.UTC(day.getFullYear(), day.getMonth(), day.getDate())).toISOString();
}

/** The rule's price as entered, or, for a rule saved with only a cost factor, its equivalent. */
function initialPrice(rule: PricingRuleEntity | undefined, metricName: string) {
    if (!rule) {
        return { price: null, priceUnit: defaultPriceUnit(metricName), multiplier: null };
    }
    if (rule.price != null && rule.priceUnit) {
        return {
            price: rule.price,
            priceUnit: rule.priceUnit,
            multiplier: rule.multiplier ?? null,
        };
    }
    const priceUnit = defaultPriceUnit(rule.metricName);
    // round away the float noise of the conversion; the change in cost is below a millionth
    const price = Number(priceFromCostFactor(rule.costFactor, priceUnit).toPrecision(6));
    return { price, priceUnit, multiplier: null };
}

@Component({
    selector: 'app-pricing-rule-save',
    imports: [
        MatDialogTitle,
        MatDialogContent,
        MatDialogActions,
        MatDialogClose,
        MatButton,
        MatFormField,
        MatLabel,
        MatHint,
        MatError,
        MatPrefix,
        MatInput,
        MatSelect,
        MatAutocomplete,
        MatAutocompleteTrigger,
        MatOption,
        ReactiveFormsModule,
        MatRadioGroup,
        MatRadioButton,
        MatDatepicker,
        MatDatepickerInput,
        MatDatepickerToggle,
        MatSuffix,
        DatePipe,
    ],
    templateUrl: './pricing-rule-save.component.html',
    styleUrl: './pricing-rule-save.component.scss',
})
export class PricingRuleSaveComponent {
    private savePricingRule = inject(SavePricingRuleGQL);
    private dialogRef = inject<MatDialogRef<PricingRuleSaveComponent>>(MatDialogRef);
    private snackBar = inject(MatSnackBar);
    protected data = inject<PricingRuleSaveData>(MAT_DIALOG_DATA);

    protected editing = !!this.data.rule;
    protected units = PRICE_UNITS;

    private startName = this.data.rule?.metricName ?? this.data.metricName ?? '';
    private start = initialPrice(this.data.rule, this.startName);

    protected form = inject(NonNullableFormBuilder).group({
        metricName: [{ value: this.startName, disabled: this.editing }, Validators.required],
        baseCost: [this.data.rule?.baseCost ?? 0, Validators.required],
        price: [this.start.price as number | null, [Validators.required, Validators.min(0)]],
        priceUnit: [this.start.priceUnit, Validators.required],
        multiplier: [this.start.multiplier as number | null, Validators.min(0.000001)],
        multiplierLabel: [this.data.rule?.multiplierLabel ?? ''],
        // 'always': correct the current price (or, for a new rule, price every day);
        // 'from': a new price from a day on, the current one keeps the days before
        applies: ['always' as 'always' | 'from'],
        from: [null as Date | null],
    });

    /** The current price's start, for the hint; null when it has always applied. */
    protected currentFrom = this.data.rule?.validFrom
        ? new Date(String(this.data.rule.validFrom))
        : null;

    /**
     * The first day a new price can start: the day after the current price's, as the picker's
     * local calendar day (the picked day is read as that day in UTC).
     */
    protected earliestFrom = this.currentFrom
        ? new Date(
              this.currentFrom.getUTCFullYear(),
              this.currentFrom.getUTCMonth(),
              this.currentFrom.getUTCDate() + 1
          )
        : null;

    private metricName = toSignal(this.form.controls.metricName.valueChanges, {
        initialValue: this.form.controls.metricName.value,
    });
    private values = toSignal(this.form.valueChanges.pipe(startWith(this.form.getRawValue())), {
        requireSync: true,
    });

    protected suggestions = computed(() => {
        const typed = this.metricName().trim().toLowerCase();
        return this.data.metricNames.filter(n => n.toLowerCase().includes(typed)).slice(0, 20);
    });

    /** Creating a rule for a metric that has one would silently replace it. */
    protected replacesExisting = computed(
        () => !this.editing && this.data.pricedMetricNames.includes(this.metricName().trim())
    );

    /** What the aggregator will multiply the raw value by, shown so the price is checkable. */
    protected costFactor = computed(() => {
        const { price, priceUnit, multiplier } = this.values();
        return price == null || !priceUnit ? null : costFactorOf(price, priceUnit, multiplier);
    });
    protected perRawUnit = computed(() =>
        this.values().priceUnit === PriceUnit.Unit ? 'unit' : 'byte'
    );

    constructor() {
        // follow the metric's usual unit until the user picks one
        this.form.controls.metricName.valueChanges.subscribe(name => {
            if (!this.form.controls.priceUnit.dirty) {
                this.form.controls.priceUnit.setValue(defaultPriceUnit(name));
            }
        });
    }

    /** A new price from a day needs the day, after the current price's start. */
    protected missingFrom = computed(() => this.values().applies === 'from' && !this.values().from);

    save() {
        if (this.form.invalid || this.missingFrom()) {
            return;
        }
        const {
            metricName,
            baseCost,
            price,
            priceUnit,
            multiplier,
            multiplierLabel,
            applies,
            from,
        } = this.form.getRawValue();
        const request = {
            metricName: metricName.trim(),
            baseCost,
            price,
            priceUnit,
            multiplier: multiplier || null,
            multiplierLabel: multiplier ? multiplierLabel.trim() || null : null,
            // whole UTC days, like the bills and Cost Overview
            validFrom: applies === 'from' && from ? utcDayStart(from) : null,
        };
        this.savePricingRule.mutate({ variables: { request } }).subscribe({
            next: result => {
                if (result.error) {
                    this.snackBar.open(`Saving failed: ${result.error.message}`, 'close');
                    return;
                }
                this.snackBar.open('Pricing rule saved', 'close', {
                    politeness: 'polite',
                    duration: 2000,
                });
                this.dialogRef.close(result.data?.savePricingRule);
            },
            error: err => this.snackBar.open(`Saving failed: ${err.message}`, 'close'),
        });
    }
}
