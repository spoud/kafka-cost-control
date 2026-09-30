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
import { MatAutocomplete, MatAutocompleteTrigger, MatOption } from '@angular/material/autocomplete';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatSnackBar } from '@angular/material/snack-bar';
import { SavePricingRuleGQL } from '../../../generated/graphql/sdk';
import { PricingRuleEntity } from '../../../generated/graphql/types';
import { costFactorFromPerGb, costFactorPerGb } from '../pricing-rules-list/cost-factor.pipe';

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
        MatAutocomplete,
        MatAutocompleteTrigger,
        MatOption,
        ReactiveFormsModule,
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

    protected form = inject(NonNullableFormBuilder).group({
        metricName: [
            {
                value: this.data.rule?.metricName ?? this.data.metricName ?? '',
                disabled: this.editing,
            },
            Validators.required,
        ],
        baseCost: [this.data.rule?.baseCost ?? 0, Validators.required],
        costFactor: [this.data.rule?.costFactor ?? 0, Validators.required],
        pricePerGb: [0],
    });

    private metricName = toSignal(this.form.controls.metricName.valueChanges, {
        initialValue: this.form.controls.metricName.value,
    });

    /** Byte metrics are easier to price per GB than per byte, so they get a second, linked field. */
    protected isBytes = computed(() => this.metricName().trim().endsWith('bytes'));

    protected suggestions = computed(() => {
        const typed = this.metricName().trim().toLowerCase();
        return this.data.metricNames.filter(n => n.toLowerCase().includes(typed)).slice(0, 20);
    });

    /** Creating a rule for a metric that has one would silently replace it. */
    protected replacesExisting = computed(
        () => !this.editing && this.data.pricedMetricNames.includes(this.metricName().trim())
    );

    constructor() {
        const { costFactor, pricePerGb } = this.form.controls;
        pricePerGb.setValue(costFactorPerGb('bytes', costFactor.value) ?? 0);
        costFactor.valueChanges.subscribe(value =>
            pricePerGb.setValue(costFactorPerGb('bytes', value ?? 0) ?? 0, { emitEvent: false })
        );
        pricePerGb.valueChanges.subscribe(value =>
            costFactor.setValue(costFactorFromPerGb(value ?? 0), { emitEvent: false })
        );
    }

    save() {
        const { metricName, baseCost, costFactor } = this.form.getRawValue();
        const request = { metricName: metricName.trim(), baseCost, costFactor };
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
