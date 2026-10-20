import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNativeDateAdapter } from '@angular/material/core';
import { of } from 'rxjs';
import { ClustersGQL, SaveBillGQL } from '../../../generated/graphql/sdk';
import { Allocation } from '../../../generated/graphql/types';
import { GraphFilterService } from '../../tab-graphs/graph-filter/graph-filter.service';
import { signal } from '@angular/core';
import { LineTemplate, PRESETS } from '../bill';
import { BillSaveComponent, BillSaveData } from './bill-save.component';

const SELF_HOSTED = PRESETS.find(p => p.name === 'Self-hosted')!.lines;

function setup(data: BillSaveData) {
    const mutate = vi.fn((options: { variables: { request: Record<string, unknown> } }) =>
        of({ data: { saveBill: { ...options.variables.request } } })
    );
    const close = vi.fn();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
        imports: [BillSaveComponent],
        providers: [
            { provide: SaveBillGQL, useValue: { mutate } },
            {
                provide: ClustersGQL,
                useValue: { fetch: () => of({ data: { clusters: ['poc-cluster'] } }) },
            },
            { provide: MatDialogRef, useValue: { close } },
            { provide: MAT_DIALOG_DATA, useValue: data },
            {
                provide: GraphFilterService,
                useValue: {
                    contextKeys: signal(['tenant']),
                    metricNames: signal([{ metricName: 'kafka_log_log_size' }]),
                },
            },
            // the app provides it in app.config
            provideNativeDateAdapter(),
        ],
    });
    const fixture = TestBed.createComponent(BillSaveComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance as unknown as {
        form: { patchValue(v: Record<string, unknown>): void };
        save(): void;
        addLine(): void;
        usePreset(lines: readonly LineTemplate[]): void;
        metricOptions(): string[];
    };
    const el: HTMLElement = fixture.nativeElement;
    const sent = () => mutate.mock.calls[0][0].variables.request;
    return { fixture, component, el, mutate, close, sent };
}

describe('BillSaveComponent', () => {
    it("saves a cluster's month from a preset; a metrics line left empty is estimated", () => {
        const { component, fixture, sent, close } = setup({ existing: [] });
        component.usePreset(SELF_HOSTED);
        component.form.patchValue({
            month: '2026-10',
            cluster: ' poc-cluster ',
            monthToDate: false,
            lines: [{ amount: 8.5 }, { amount: null }, { amount: 120 }],
        });
        fixture.detectChanges();
        component.save();

        expect(sent()).toEqual({
            month: '2026-10',
            cluster: 'poc-cluster',
            coveredUntil: null,
            lines: [
                {
                    description: 'Storage',
                    amount: 8.5,
                    allocation: Allocation.Metrics,
                    metrics: ['kafka_log_log_size'],
                    context: [],
                },
                {
                    description: 'Network',
                    amount: null,
                    allocation: Allocation.Metrics,
                    metrics: [
                        'kafka_server_brokertopicmetrics_bytesin_total',
                        'kafka_server_brokertopicmetrics_bytesout_total',
                    ],
                    context: [],
                },
                {
                    description: 'Brokers and operations',
                    amount: 120,
                    allocation: Allocation.Usage,
                    metrics: [],
                    context: [],
                },
            ],
        });
        expect(close).toHaveBeenCalled();
    });

    it("offers the collected metrics and the preset's, even before they are collected", () => {
        const { component, fixture } = setup({ existing: [] });
        component.usePreset(SELF_HOSTED);
        fixture.detectChanges();

        expect(component.metricOptions()).toEqual([
            'kafka_log_log_size',
            'kafka_server_brokertopicmetrics_bytesin_total',
            'kafka_server_brokertopicmetrics_bytesout_total',
        ]);
    });

    it('sends where a month-to-date bill stops: midnight after its last day; no cluster is all of them', () => {
        const { component, fixture, sent } = setup({ existing: [] });
        component.usePreset(SELF_HOSTED);
        component.form.patchValue({
            month: '2026-10',
            monthToDate: true,
            lastDay: new Date(2026, 9, 5),
            // the line spread by usage needs an amount
            lines: [{ amount: 0.8163 }, {}, { amount: 50 }],
        });
        fixture.detectChanges();
        component.save();

        expect(sent()).toMatchObject({
            month: '2026-10',
            cluster: null,
            coveredUntil: '2026-10-06T00:00:00.000Z',
        });
    });

    it('does not save without an amount, a negative metrics line, or one without metrics', () => {
        const { component, fixture, mutate, el } = setup({ existing: [] });
        component.usePreset(SELF_HOSTED);
        component.form.patchValue({ month: '2026-09', monthToDate: false });
        fixture.detectChanges();
        component.save();
        component.form.patchValue({ lines: [{ amount: -1 }] });
        fixture.detectChanges();
        component.save();
        expect(el.textContent).toContain('Only lines not shared by metrics can be negative');
        component.form.patchValue({ lines: [{ amount: 1, metrics: [] }] });
        fixture.detectChanges();
        component.save();

        expect(mutate).not.toHaveBeenCalled();
        expect(el.textContent).toContain('one shared by metrics at least one metric');
    });

    it('does not save a metric on two lines', () => {
        const { component, fixture, mutate, el } = setup({ existing: [] });
        component.usePreset(SELF_HOSTED);
        component.form.patchValue({
            month: '2026-09',
            monthToDate: false,
            lines: [{ amount: 1 }, { amount: 2, metrics: ['kafka_log_log_size'] }],
        });
        fixture.detectChanges();
        component.save();

        expect(mutate).not.toHaveBeenCalled();
        expect(el.textContent).toContain('kafka_log_log_size is on two lines');
    });

    it("warns that saving replaces the month's bill for the cluster, and refuses mixing all and per cluster", () => {
        const adding = setup({ existing: [{ month: '2026-09', cluster: 'poc-cluster' }] });
        adding.component.usePreset(SELF_HOSTED);
        adding.component.form.patchValue({
            month: '2026-09',
            cluster: 'poc-cluster',
            monthToDate: false,
            lines: [{ amount: 1 }],
        });
        adding.fixture.detectChanges();
        expect(adding.el.textContent).toContain('saving replaces it');

        adding.component.form.patchValue({ cluster: '' });
        adding.fixture.detectChanges();
        adding.component.save();
        expect(adding.el.textContent).toContain('has bills per cluster');
        expect(adding.mutate).not.toHaveBeenCalled();
    });

    it('keeps an edited bill on its month and cluster', () => {
        const editing = setup({
            existing: [{ month: '2026-08', cluster: 'poc-cluster' }],
            bill: {
                month: '2026-08',
                cluster: 'poc-cluster',
                lines: [
                    {
                        description: 'Storage',
                        amount: 1,
                        allocation: Allocation.Metrics,
                        metrics: ['kafka_log_log_size'],
                    },
                ],
                updatedAt: '2026-09-01T00:00:00Z',
            },
        });
        expect(editing.el.textContent).toContain('Edit bill');
        expect(editing.el.querySelector('mat-select')?.getAttribute('aria-disabled')).toBe('true');
        expect(
            (editing.el.querySelector('input[formcontrolname="cluster"]') as HTMLInputElement)
                .disabled
        ).toBe(true);
    });

    it('sends other lines with where they go; a context line only with its pairs', () => {
        const { component, fixture, sent } = setup({ existing: [] });
        component.addLine();
        component.addLine();
        component.form.patchValue({
            month: '2026-09',
            monthToDate: false,
            lines: [
                {
                    description: ' Connect ',
                    amount: 12,
                    allocation: Allocation.Context,
                    context: [{ key: 'application', value: ' etl ' }],
                },
                { description: 'Credit', amount: -2, allocation: Allocation.Shared },
            ],
        });
        fixture.detectChanges();
        component.save();

        expect(sent()['lines']).toEqual([
            {
                description: 'Connect',
                amount: 12,
                allocation: Allocation.Context,
                metrics: [],
                context: [{ key: 'application', value: 'etl' }],
            },
            {
                description: 'Credit',
                amount: -2,
                allocation: Allocation.Shared,
                metrics: [],
                context: [],
            },
        ]);
    });

    it('does not save a line going to a context without one', () => {
        const { component, fixture, mutate, el } = setup({ existing: [] });
        component.addLine();
        component.form.patchValue({
            month: '2026-09',
            monthToDate: false,
            lines: [{ description: 'Connect', amount: 12, allocation: Allocation.Context }],
        });
        fixture.detectChanges();
        component.save();

        expect(mutate).not.toHaveBeenCalled();
        expect(el.textContent).toContain('one going to a context a key and value');
    });
});
