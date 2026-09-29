import { ApplicationRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { SavePricingRuleGQL } from '../../../generated/graphql/sdk';
import { PricingRuleSaveComponent, PricingRuleSaveData } from './pricing-rule-save.component';
import { costFactorFromPerGb, costFactorPerGb } from '../pricing-rules-list/cost-factor.pipe';

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
    return { fixture, el, input, type, submit, mutate, close };
}

describe('PricingRuleSaveComponent', () => {
    it('edits an existing rule without letting its metric change', async () => {
        const rule = {
            metricName: 'confluent_kafka_server_request_bytes',
            baseCost: 0,
            costFactor: 1e-10,
            creationTime: '2026-09-29T00:00:00Z',
        };
        const { input, type, submit, mutate, close } = setup({
            rule,
            metricNames: [],
            pricedMetricNames: [rule.metricName],
        });
        // the autocomplete trigger writes the input's value asynchronously
        await TestBed.inject(ApplicationRef).whenStable();

        expect(input('metricName').disabled).toBe(true);
        expect(input('metricName').value).toBe(rule.metricName);

        type('baseCost', '2');
        submit();

        expect(mutate).toHaveBeenCalledWith({
            variables: {
                request: { metricName: rule.metricName, baseCost: 2, costFactor: 1e-10 },
            },
        });
        expect(close).toHaveBeenCalledWith({
            metricName: rule.metricName,
            baseCost: 2,
            costFactor: 1e-10,
        });
    });

    it('sets the cost factor of a byte metric from a price per GB', () => {
        const { type, input, submit, mutate } = setup({
            metricName: 'confluent_kafka_server_response_bytes',
            metricNames: [],
            pricedMetricNames: [],
        });

        type('pricePerGb', '0.1265');

        expect(Number(input('costFactor').value)).toBeCloseTo(0.1265 / GB, 20);
        submit();
        const request = mutate.mock.calls[0][0].variables.request as { costFactor: number };
        expect(request.costFactor).toBeCloseTo(0.1265 / GB, 20);
    });

    it('offers no price per GB for metrics that are not bytes', () => {
        const { el } = setup({
            metricName: 'kafka_topic_partition_count',
            metricNames: [],
            pricedMetricNames: [],
        });

        expect(el.querySelector('input[formcontrolname="pricePerGb"]')).toBeNull();
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

describe('cost factor per GB', () => {
    it('converts both ways for byte metrics only', () => {
        expect(costFactorPerGb('x_bytes', costFactorFromPerGb(0.1265))).toBe(0.1265);
        expect(costFactorPerGb('partition_count', 1)).toBeNull();
    });
});
