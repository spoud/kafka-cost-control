import { Component, computed, inject, OnInit, signal, TemplateRef, ViewChild } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { MatTableModule } from '@angular/material/table';
import { MatButton, MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MatTooltip } from '@angular/material/tooltip';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { BillsGQL, DeleteBillGQL } from '../../../generated/graphql/sdk';
import { BillEntity } from '../../../generated/graphql/types';
import { PageHeaderComponent } from '../../common/page-header/page-header.component';
import { DataTableComponent } from '../../common/data-table/data-table.component';
import { IntlDatePipe } from '../../common/intl-date.pipe';
import { ConfirmDialogComponent } from '../../common/confirm-dialog/confirm-dialog.component';
import { BillSaveComponent, BillSaveData } from '../bill-save/bill-save.component';
import {
    BILL_LINES,
    billTotal,
    describeOtherLine,
    lastDayFromCoveredUntil,
    monthLabel,
    otherTotal,
} from '../bill';

/**
 * What the provider billed, month by month. Costs on Cost Overview share each month's bill among
 * topics and principals by usage; months without a bill are estimated from the pricing rules.
 */
@Component({
    selector: 'app-bills-list',
    imports: [
        MatTableModule,
        MatButton,
        MatIconButton,
        MatIcon,
        MatTooltip,
        DecimalPipe,
        IntlDatePipe,
        PageHeaderComponent,
        DataTableComponent,
    ],
    templateUrl: './bills-list.component.html',
    styleUrl: './bills-list.component.scss',
})
export class BillsListComponent implements OnInit {
    private billsGql = inject(BillsGQL);
    private deleteBillGql = inject(DeleteBillGQL);
    private dialog = inject(MatDialog);
    private snackBar = inject(MatSnackBar);

    @ViewChild('deleteConfirmContent') deleteConfirmContent!: TemplateRef<unknown>;

    protected readonly lines = BILL_LINES;
    protected readonly monthLabel = monthLabel;
    protected readonly billTotal = billTotal;
    protected readonly otherTotal = otherTotal;
    protected readonly otherDetail = (bill: BillEntity) =>
        bill.otherLines.map(describeOtherLine).join('\n');
    protected readonly lastDay = lastDayFromCoveredUntil;
    protected readonly columns = [
        'month',
        ...BILL_LINES.map(line => line.key),
        'other',
        'total',
        'updated',
        'buttons',
    ];

    protected readonly bills = signal<BillEntity[]>([]);
    protected readonly loading = signal(true);
    protected readonly error = signal<string | null>(null);
    protected readonly empty = computed(
        () => !this.loading() && !this.error() && this.bills().length === 0
    );

    ngOnInit(): void {
        this.load();
    }

    private load(): void {
        this.loading.set(true);
        this.billsGql.fetch({ fetchPolicy: 'network-only' }).subscribe({
            next: result => {
                this.loading.set(false);
                if (result.error) {
                    this.error.set(result.error.message);
                } else {
                    this.error.set(null);
                    this.bills.set(result.data?.bills ?? []);
                }
            },
            error: err => {
                this.loading.set(false);
                this.error.set(err.message);
            },
        });
    }

    protected openSaveDialog(bill?: BillEntity): void {
        const data: BillSaveData = { bill, billedMonths: this.bills().map(b => b.month) };
        this.dialog
            .open(BillSaveComponent, { data, width: '720px', maxWidth: '95vw' })
            .afterClosed()
            .subscribe((saved?: BillEntity) => {
                if (saved) {
                    this.snackBar.open(`Bill for ${monthLabel(saved.month)} saved`, 'close', {
                        politeness: 'polite',
                        duration: 2000,
                    });
                    this.load();
                }
            });
    }

    protected delete(bill: BillEntity): void {
        this.dialog
            .open(ConfirmDialogComponent, {
                data: {
                    title: 'Delete bill',
                    contentTemplate: this.deleteConfirmContent,
                    templateContext: { $implicit: bill },
                    confirmLabel: 'Yes, delete',
                    cancelLabel: 'No, cancel',
                    destructive: true,
                },
            })
            .afterClosed()
            .subscribe(confirmed => {
                if (!confirmed) {
                    return;
                }
                this.deleteBillGql.mutate({ variables: { month: bill.month } }).subscribe({
                    next: result => {
                        if (result.error) {
                            this.snackBar.open(`Deleting failed: ${result.error.message}`, 'close');
                            return;
                        }
                        this.snackBar.open('Bill deleted', 'close', {
                            politeness: 'polite',
                            duration: 2000,
                        });
                        this.load();
                    },
                    error: err => this.snackBar.open(`Deleting failed: ${err.message}`, 'close'),
                });
            });
    }
}
