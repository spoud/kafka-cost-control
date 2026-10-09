import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import {
    MAT_DIALOG_DATA,
    MatDialogActions,
    MatDialogClose,
    MatDialogContent,
    MatDialogRef,
    MatDialogTitle,
} from '@angular/material/dialog';
import { MatButton } from '@angular/material/button';
import { MatCheckbox } from '@angular/material/checkbox';
import { MatRadioButton, MatRadioGroup } from '@angular/material/radio';
import { LoadingIndicatorComponent } from '../../common/loading-indicator/loading-indicator.component';
import { forkJoin, map, of, catchError } from 'rxjs';
import {
    BillRatesGQL,
    GetPricingRulesGQL,
    SavePricingRuleGQL,
} from '../../../generated/graphql/sdk';
import { BillEntity, PriceUnit, PricingRuleEntity } from '../../../generated/graphql/types';
import { defaultPriceUnit, formatPrice, formatUsage, priceOf } from '../../tab-pricing-rules/price';
import { monthLabel, ratesFrom } from '../bill';

export interface ApplyBillRatesData {
    bill: BillEntity;
}

/** One bill line, the price it works out to and the rule it would replace. */
export interface RateRow {
    metricName: string;
    amount: number;
    usage: string;
    rule?: PricingRuleEntity;
    unit: PriceUnit;
    multiplier: number | null;
    multiplierLabel: string | null;
    /** The bill's rate as a price in the rule's unit; null when no usage was measured. */
    price: number | null;
    newPrice: string | null;
    currentPrice: string | null;
}

/**
 * Saves a bill's rates (amount / measured usage) as the pricing rules' prices, so the hours after
 * the bill are estimated at what the provider really charged. The rule keeps its unit and
 * multiplier; the base cost goes to 0, since the rate alone reproduces the bill.
 */
@Component({
    selector: 'app-apply-bill-rates',
    imports: [
        MatDialogTitle,
        MatDialogContent,
        MatDialogActions,
        MatDialogClose,
        MatButton,
        MatCheckbox,
        MatRadioGroup,
        MatRadioButton,
        LoadingIndicatorComponent,
        DatePipe,
        DecimalPipe,
    ],
    templateUrl: './apply-bill-rates.component.html',
    styleUrl: './apply-bill-rates.component.scss',
})
export class ApplyBillRatesComponent implements OnInit {
    private billRatesGql = inject(BillRatesGQL);
    private pricingRulesGql = inject(GetPricingRulesGQL);
    private savePricingRule = inject(SavePricingRuleGQL);
    private dialogRef = inject<MatDialogRef<ApplyBillRatesComponent>>(MatDialogRef);
    protected data = inject<ApplyBillRatesData>(MAT_DIALOG_DATA);

    protected readonly month = monthLabel(this.data.bill.month);
    /** Midnight UTC of the day the bill ends: where the new prices start by default. */
    protected readonly from = ratesFrom(this.data.bill);

    protected readonly rows = signal<RateRow[]>([]);
    protected readonly loading = signal(true);
    protected readonly saving = signal(false);
    protected readonly error = signal<string | null>(null);
    protected readonly applies = signal<'from' | 'always'>('from');
    protected readonly selected = signal<ReadonlySet<string>>(new Set());

    /** Why a row can't be applied, or null. */
    protected blocked(row: RateRow): string | null {
        if (row.price == null) {
            return 'No usage measured in the hours the bill covers.';
        }
        const current = row.rule?.validFrom ? String(row.rule.validFrom) : null;
        if (this.applies() === 'from' && current && new Date(current) >= new Date(this.from)) {
            return `Its current price starts ${current.slice(0, 10)}; a new one must start after it.`;
        }
        return null;
    }

    protected readonly chosen = computed(() =>
        this.rows().filter(row => this.selected().has(row.metricName) && !this.blocked(row))
    );
    protected readonly resetsBaseCost = computed(() =>
        this.chosen().some(row => (row.rule?.baseCost ?? 0) !== 0)
    );

    ngOnInit(): void {
        forkJoin({
            rates: this.billRatesGql.fetch({
                variables: { month: this.data.bill.month },
                fetchPolicy: 'network-only',
            }),
            rules: this.pricingRulesGql.fetch({ fetchPolicy: 'network-only' }),
        }).subscribe({
            next: ({ rates, rules }) => {
                this.loading.set(false);
                const failed = rates.error ?? rules.error;
                if (failed) {
                    this.error.set(failed.message);
                    return;
                }
                const byMetric = new Map(
                    (rules.data?.pricingRules ?? []).map(rule => [rule.metricName, rule])
                );
                this.rows.set(
                    (rates.data?.billRates ?? []).map(rate =>
                        rateRow(
                            rate,
                            byMetric.get(rate.metricName) as PricingRuleEntity | undefined
                        )
                    )
                );
                this.selected.set(
                    new Set(
                        this.rows()
                            .filter(r => !this.blocked(r))
                            .map(r => r.metricName)
                    )
                );
            },
            error: err => {
                this.loading.set(false);
                this.error.set(err.message);
            },
        });
    }

    protected toggle(metricName: string, checked: boolean): void {
        const next = new Set(this.selected());
        if (checked) {
            next.add(metricName);
        } else {
            next.delete(metricName);
        }
        this.selected.set(next);
    }

    apply(): void {
        const rows = this.chosen();
        if (!rows.length || this.saving()) {
            return;
        }
        this.saving.set(true);
        this.error.set(null);
        const validFrom = this.applies() === 'from' ? this.from : null;
        forkJoin(
            rows.map(row =>
                this.savePricingRule
                    .mutate({
                        variables: {
                            request: {
                                metricName: row.metricName,
                                baseCost: 0,
                                price: row.price,
                                priceUnit: row.unit,
                                multiplier: row.multiplier,
                                multiplierLabel: row.multiplier ? row.multiplierLabel : null,
                                validFrom,
                            },
                        },
                    })
                    .pipe(
                        map(result =>
                            result.error ? `${row.metricName}: ${result.error.message}` : null
                        ),
                        catchError(err => of(`${row.metricName}: ${err.message}`))
                    )
            )
        ).subscribe(failures => {
            this.saving.set(false);
            const failed = failures.filter((f): f is string => !!f);
            if (failed.length) {
                // some may have been saved; the rules page shows which
                this.error.set(`Not saved: ${failed.join('; ')}`);
                return;
            }
            this.dialogRef.close(rows.length);
        });
    }
}

function rateRow(
    rate: { metricName: string; amount: number; usage: number; costFactor?: number | null },
    rule: PricingRuleEntity | undefined
): RateRow {
    // keep how the rule reads (e.g. per GB-hour × 3 replicas); a rule saved with only a cost
    // factor, or none, gets its metric's usual unit
    const unit = rule?.priceUnit ?? defaultPriceUnit(rate.metricName);
    const multiplier = rule?.priceUnit ? (rule.multiplier ?? null) : null;
    const multiplierLabel = multiplier ? (rule?.multiplierLabel ?? null) : null;
    const price = rate.costFactor == null ? null : priceOf(rate.costFactor, unit, multiplier);
    return {
        metricName: rate.metricName,
        amount: rate.amount,
        usage: formatUsage(rate.usage, unit),
        rule,
        unit,
        multiplier,
        multiplierLabel,
        price,
        newPrice:
            price == null
                ? null
                : formatPrice({
                      metricName: rate.metricName,
                      costFactor: rate.costFactor!,
                      price,
                      priceUnit: unit,
                      multiplier,
                      multiplierLabel,
                  }),
        currentPrice: rule ? formatPrice(rule) : null,
    };
}
