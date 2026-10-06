import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNativeDateAdapter } from '@angular/material/core';
import { of } from 'rxjs';
import { SaveBillGQL } from '../../../generated/graphql/sdk';
import { BillSaveComponent, BillSaveData } from './bill-save.component';

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
            { provide: MatDialogRef, useValue: { close } },
            { provide: MAT_DIALOG_DATA, useValue: data },
            // the app provides it in app.config
            provideNativeDateAdapter(),
        ],
    });
    const fixture = TestBed.createComponent(BillSaveComponent);
    fixture.detectChanges();
    const component = fixture.componentInstance as unknown as {
        form: { patchValue(v: Record<string, unknown>): void };
        save(): void;
    };
    const el: HTMLElement = fixture.nativeElement;
    const sent = () => mutate.mock.calls[0][0].variables.request;
    return { fixture, component, el, mutate, close, sent };
}

describe('BillSaveComponent', () => {
    it('saves a whole month with the lines entered', () => {
        const { component, fixture, sent, close } = setup({ billedMonths: [] });
        component.form.patchValue({
            month: '2026-09',
            monthToDate: false,
            networkWrite: 0.2685,
            partitions: 22.5,
        });
        fixture.detectChanges();
        component.save();

        expect(sent()).toMatchObject({
            month: '2026-09',
            coveredUntil: null,
            networkWrite: 0.2685,
            networkRead: null,
            partitions: 22.5,
        });
        expect(close).toHaveBeenCalled();
    });

    it('sends where a month-to-date bill stops: midnight after its last day', () => {
        const { component, fixture, sent } = setup({ billedMonths: [] });
        component.form.patchValue({
            month: '2026-10',
            monthToDate: true,
            lastDay: new Date(2026, 9, 5),
            networkWrite: 0.8163,
        });
        fixture.detectChanges();
        component.save();

        expect(sent()).toMatchObject({
            month: '2026-10',
            coveredUntil: '2026-10-06T00:00:00.000Z',
        });
    });

    it('does not save without an amount, or with a negative line', () => {
        const { component, fixture, mutate } = setup({ billedMonths: [] });
        component.form.patchValue({ month: '2026-09', monthToDate: false });
        fixture.detectChanges();
        component.save();
        component.form.patchValue({ storage: -1 });
        fixture.detectChanges();
        component.save();

        expect(mutate).not.toHaveBeenCalled();
    });

    it('warns that saving replaces an existing month, and keeps an edited bill on its month', () => {
        const adding = setup({ billedMonths: ['2026-09'] });
        adding.component.form.patchValue({ month: '2026-09' });
        adding.fixture.detectChanges();
        expect(adding.el.textContent).toContain('saving replaces it');

        const editing = setup({
            billedMonths: ['2026-08'],
            bill: {
                month: '2026-08',
                networkWrite: 1,
                updatedAt: '2026-09-01T00:00:00Z',
            },
        });
        expect(editing.el.textContent).toContain('Edit bill');
        expect(editing.el.querySelector('mat-select')?.getAttribute('aria-disabled')).toBe('true');
    });
});
