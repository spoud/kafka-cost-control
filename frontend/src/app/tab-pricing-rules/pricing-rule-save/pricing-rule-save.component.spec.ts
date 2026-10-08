import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNativeDateAdapter } from '@angular/material/core';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { SavePricingRuleGQL } from '../../../generated/graphql/sdk';
import { PriceUnit } from '../../../generated/graphql/types';
import { PricingRuleSaveComponent, PricingRuleSaveData } from './pricing-rule-save.component';

const GB = 1024 * 1024 * 1024;

function setup(data: PricingRuleSaveData) {
    const mutate = vi.fn((options: { variables: { request: unknown } }) =>
        of({ data: { savePricingRule: options.variables.request } })
    );
    const close = vi.fn();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
        imports: [PricingRuleSaveComponent],
        providers: [
            { provide: SavePricingRuleGQL, useValue: { mutate } },
            { provide: MatDialogRef, useValue: { close } },
            { provide: MAT_DIALOG_DATA, useValue: data },
            // the app provides it in app.config
            provideNativeDateAdapter(),
        ],
    });
    const fixture = TestBed.createComponent(PricingRuleSaveComponent);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const input = (name: string) =>
        el.querySelector<HTMLInputElement>(`input[formcontrolname="${name}"]`)!;
    const type = (name: string, value: string) => {
        input(name).value = value;
        input(name).dispatchEvent(new Event('input'));
        fixture.detectChanges();
    };
    const submit = () => {
        el.querySelector('form')!.dispatchEvent(new Event('submit'));
        fixture.detectChanges();
    };
    const sent = () => mutate.mock.calls[0][0].variables.request as Record<string, unknown>;
    return { fixture, el, input, type, submit, mutate, close, sent };
}

describe('PricingRuleSaveComponent', () => {
    it('saves a price per GB-hour times a multiplier, as entered', () => {
        const { type, submit, sent, el } = setup({
            metricName: 'confluent_kafka_server_retained_bytes',
            metricNames: [],
            pricedMetricNames: [],
        });

        type('price', '0.00012603');
        type('multiplier', '3');
        type('multiplierLabel', 'replicas');
        expect(el.textContent).toContain(((3 * 0.00012603) / GB).toExponential(4));
        submit();

        expect(sent()).toEqual({
            metricName: 'confluent_kafka_server_retained_bytes',
            baseCost: 0,
            price: 0.00012603,
            priceUnit: PriceUnit.GbHour,
            multiplier: 3,
            multiplierLabel: 'replicas',
            validFrom: null,
        });
    });

    it('starts a new price from a day on, as a whole UTC day', () => {
        const rule = {
            metricName: 'confluent_kafka_server_request_bytes',
            baseCost: 0,
            costFactor: 0.1495 / GB,
            price: 0.1495,
            priceUnit: PriceUnit.Gb,
            creationTime: '2026-09-29T00:00:00Z',
            earlierPrices: [],
        };
        const { fixture, submit, sent } = setup({
            rule,
            metricNames: [],
            pricedMetricNames: [rule.metricName],
        });
        const form = (
            fixture.componentInstance as unknown as {
                form: { patchValue(v: Record<string, unknown>): void };
            }
        ).form;
        form.patchValue({ price: 0.16, applies: 'from', from: new Date(2026, 10, 1) });
        fixture.detectChanges();
        submit();

        expect(sent()).toMatchObject({ price: 0.16, validFrom: '2026-11-01T00:00:00.000Z' });
    });

    it('does not save a new price from a day without the day', () => {
        const { fixture, submit, mutate } = setup({
            metricName: 'confluent_kafka_server_request_bytes',
            metricNames: [],
            pricedMetricNames: [],
        });
        const form = (
            fixture.componentInstance as unknown as {
                form: { patchValue(v: Record<string, unknown>): void };
            }
        ).form;
        form.patchValue({ price: 0.16, applies: 'from' });
        fixture.detectChanges();
        submit();

        expect(mutate).not.toHaveBeenCalled();
    });

    it('drops the multiplier label when there is no multiplier', () => {
        const { type, submit, sent } = setup({
            metricName: 'confluent_kafka_server_request_bytes',
            metricNames: [],
            pricedMetricNames: [],
        });

        type('price', '0.1495');
        type('multiplierLabel', 'replicas');
        submit();

        expect(sent()).toMatchObject({
            priceUnit: PriceUnit.Gb,
            multiplier: null,
            multiplierLabel: null,
        });
    });

    it('opens a rule saved with only a cost factor as its price per GB', async () => {
        const rule = {
            metricName: 'confluent_kafka_server_request_bytes',
            baseCost: 0,
            costFactor: 0.1495 / GB,
            creationTime: '2026-09-29T00:00:00Z',
            earlierPrices: [],
        };
        const { input, submit, sent, close } = setup({
            rule,
            metricNames: [],
            pricedMetricNames: [rule.metricName],
        });
        // the autocomplete trigger writes the input's value asynchronously
        await TestBed.inject(ApplicationRef).whenStable();

        expect(input('metricName').disabled).toBe(true);
        expect(input('metricName').value).toBe(rule.metricName);
        expect(Number(input('price').value)).toBeCloseTo(0.1495, 10);

        submit();
        expect(sent()).toMatchObject({ metricName: rule.metricName, priceUnit: PriceUnit.Gb });
        expect(close).toHaveBeenCalled();
    });

    it('requires a price', () => {
        const { submit, mutate } = setup({
            metricName: 'kafka_topic_partition_count',
            metricNames: [],
            pricedMetricNames: [],
        });

        submit();

        expect(mutate).not.toHaveBeenCalled();
    });

    it('warns when a new rule would replace an existing one', () => {
        const { el, type } = setup({
            metricNames: [],
            pricedMetricNames: ['confluent_kafka_server_retained_bytes'],
        });
        expect(el.textContent).not.toContain('saving replaces it');

        type('metricName', 'confluent_kafka_server_retained_bytes');

        expect(el.textContent).toContain('saving replaces it');
    });
});
