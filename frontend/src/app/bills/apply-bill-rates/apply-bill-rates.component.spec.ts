import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import {
    BillRatesGQL,
    GetPricingRulesGQL,
    SavePricingRuleGQL,
} from '../../../generated/graphql/sdk';
import { BillEntity, PriceUnit } from '../../../generated/graphql/types';
import { ApplyBillRatesComponent } from './apply-bill-rates.component';

const GB = 1024 * 1024 * 1024;
const WRITE = 'confluent_kafka_server_request_bytes';
const STORAGE = 'confluent_kafka_server_retained_bytes';
const PARTITIONS = 'kafka_topic_partition_count';

const BILL = {
    month: '2026-10',
    coveredUntil: null,
    otherLines: [],
} as unknown as BillEntity;

const rule = (metricName: string, extra: Record<string, unknown>) => ({
    metricName,
    baseCost: 0,
    costFactor: 0,
    creationTime: '2026-09-29T00:00:00Z',
    earlierPrices: [],
    ...extra,
});

function setup(rates: unknown[], rules: unknown[]) {
    const mutate = vi.fn((options: { variables: { request: unknown } }) =>
        of({ data: { savePricingRule: options.variables.request } })
    );
    const close = vi.fn();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
        imports: [ApplyBillRatesComponent],
        providers: [
            {
                provide: BillRatesGQL,
                useValue: { fetch: () => of({ data: { billRates: rates } }) },
            },
            {
                provide: GetPricingRulesGQL,
                useValue: { fetch: () => of({ data: { pricingRules: rules } }) },
            },
            { provide: SavePricingRuleGQL, useValue: { mutate } },
            { provide: MatDialogRef, useValue: { close } },
            { provide: MAT_DIALOG_DATA, useValue: { bill: BILL } },
        ],
    });
    const fixture = TestBed.createComponent(ApplyBillRatesComponent);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const button = () =>
        [...el.querySelectorAll<HTMLButtonElement>('button')].find(b =>
            b.textContent!.includes('Apply')
        )!;
    const apply = () => {
        button().click();
        fixture.detectChanges();
    };
    const sent = () =>
        mutate.mock.calls.map(c => c[0].variables.request as Record<string, unknown>);
    return { fixture, el, button, apply, sent, mutate, close };
}

describe('ApplyBillRatesComponent', () => {
    it("saves each line's rate in its rule's unit, from the day the bill ends", () => {
        const { el, apply, sent, close } = setup(
            [
                // $0.1495 per GB, as billed
                { metricName: WRITE, amount: 0.299, usage: 2 * GB, costFactor: 0.1495 / GB },
                // $0.000138 per GB-hour × 3 replicas
                {
                    metricName: STORAGE,
                    amount: 0.414,
                    usage: 1000 * GB,
                    costFactor: (3 * 0.000138) / GB,
                },
            ],
            [
                rule(WRITE, { price: 0.16, priceUnit: PriceUnit.Gb, baseCost: 0.01 }),
                rule(STORAGE, {
                    price: 0.00012603,
                    priceUnit: PriceUnit.GbHour,
                    multiplier: 3,
                    multiplierLabel: 'replicas',
                }),
            ]
        );

        expect(el.textContent).toContain('$0.1495 per GB');
        expect(el.textContent).toContain('$0.000138 per GB-hour × 3 replicas');
        expect(el.textContent).toContain('1,000 GB-hours');
        expect(el.textContent).toContain('base cost of these rules is set to 0');
        apply();

        expect(sent()).toHaveLength(2);
        expect(sent()[0]).toMatchObject({
            metricName: WRITE,
            baseCost: 0,
            priceUnit: PriceUnit.Gb,
            multiplier: null,
            validFrom: '2026-11-01T00:00:00.000Z',
        });
        expect(sent()[0]['price']).toBeCloseTo(0.1495, 10);
        expect(sent()[1]).toMatchObject({
            metricName: STORAGE,
            priceUnit: PriceUnit.GbHour,
            multiplier: 3,
            multiplierLabel: 'replicas',
        });
        expect(sent()[1]['price']).toBeCloseTo(0.000138, 12);
        expect(close).toHaveBeenCalledWith(2);
    });

    it('gives a metric without a rule its usual unit, and skips a line without usage', () => {
        const { el, apply, sent } = setup(
            [
                { metricName: PARTITIONS, amount: 1.7526, usage: 1365, costFactor: 1.7526 / 1365 },
                { metricName: WRITE, amount: 3, usage: 0, costFactor: null },
            ],
            []
        );

        expect(el.textContent).toContain('no rule yet');
        expect(el.textContent).toContain('No usage measured');
        expect(el.textContent).toContain('1,365 unit-hours');
        apply();

        expect(sent()).toHaveLength(1);
        expect(sent()[0]).toMatchObject({ metricName: PARTITIONS, priceUnit: PriceUnit.Unit });
        expect(sent()[0]['price']).toBeCloseTo(1.7526 / 1365, 12);
    });

    it("can't start a price before the rule's current one, but can correct it", () => {
        const { fixture, el, button, apply, sent } = setup(
            [{ metricName: WRITE, amount: 0.299, usage: 2 * GB, costFactor: 0.1495 / GB }],
            [
                rule(WRITE, {
                    price: 0.16,
                    priceUnit: PriceUnit.Gb,
                    validFrom: '2026-11-01T00:00:00Z',
                }),
            ]
        );

        expect(el.textContent).toContain('Its current price starts 2026-11-01');
        expect(button().disabled).toBe(true);

        const always = el.querySelector<HTMLInputElement>(
            'mat-radio-button[value="always"] input'
        )!;
        always.click();
        fixture.detectChanges();
        // unblocked rows aren't ticked by themselves after the switch
        el.querySelector<HTMLInputElement>('mat-checkbox input')!.click();
        fixture.detectChanges();
        apply();

        expect(sent()).toEqual([expect.objectContaining({ metricName: WRITE, validFrom: null })]);
    });
});
