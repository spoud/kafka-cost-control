import { Component, inject, OnInit, signal } from '@angular/core';
import { ConfirmDialogComponent } from '../../common/confirm-dialog/confirm-dialog.component';
import { MatDialog } from '@angular/material/dialog';
import { DeleteMetricGQL, MetricNamesGQL, ReprocessGQL } from '../../../generated/graphql/sdk';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { filter, mergeMap } from 'rxjs';
import { FormsModule } from '@angular/forms';
import { MatButton, MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MatTooltip } from '@angular/material/tooltip';
import { DatePipe } from '@angular/common';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatInputModule } from '@angular/material/input';
import { provideNativeDateAdapter } from '@angular/material/core';
import { MatCardModule } from '@angular/material/card';
import { PageHeaderComponent } from '../../common/page-header/page-header.component';
import { deleteMessage, stillArriving } from './metric-age';

/** A metric with stored data and the end of its last stored hour. */
interface StoredMetric {
    metricName: string;
    lastSeen: string;
}

@Component({
    selector: 'app-others',
    templateUrl: './others.component.html',
    styleUrl: './others.component.scss',
    imports: [
        FormsModule,
        MatButton,
        MatIconButton,
        MatIcon,
        MatTooltip,
        DatePipe,
        MatDatepickerModule,
        MatInputModule,
        MatSnackBarModule,
        MatCardModule,
        PageHeaderComponent,
    ],
    providers: [provideNativeDateAdapter()],
})
export class OthersComponent implements OnInit {
    private _dialog = inject(MatDialog);
    private _snackBar = inject(MatSnackBar);
    private _mutationReprocess = inject(ReprocessGQL);
    private _metricNames = inject(MetricNamesGQL);
    private _deleteMetric = inject(DeleteMetricGQL);

    startTime: Date | undefined;
    protected readonly metrics = signal<StoredMetric[]>([]);
    protected readonly stillArriving = stillArriving;

    ngOnInit(): void {
        this.loadMetrics();
    }

    private loadMetrics(): void {
        this._metricNames.fetch({ fetchPolicy: 'network-only' }).subscribe(result => {
            const names = (result.data?.metricNames ?? []).map(name => ({
                metricName: name.metricName,
                lastSeen: String(name.lastSeen),
            }));
            this.metrics.set(names.sort((a, b) => a.metricName.localeCompare(b.metricName)));
        });
    }

    openDeleteMetricDialog(metric: StoredMetric): void {
        this._dialog
            .open(ConfirmDialogComponent, {
                data: {
                    title: `Delete the metric ${metric.metricName}?`,
                    message: deleteMessage(metric.lastSeen),
                    confirmLabel: 'Delete',
                    cancelLabel: 'Cancel',
                    destructive: true,
                },
            })
            .afterClosed()
            .pipe(
                filter(result => result),
                mergeMap(() =>
                    this._deleteMetric.mutate({ variables: { metricName: metric.metricName } })
                )
            )
            .subscribe({
                next: result => {
                    if (result.error) {
                        this._snackBar.open('Not deleted: ' + result.error.message, 'close');
                        return;
                    }
                    this._snackBar.open(
                        `Deleted ${metric.metricName} (${result.data?.deleteMetric ?? 0} stored rows)`,
                        'close',
                        { duration: 5000 }
                    );
                    this.loadMetrics();
                },
                error: (err: Error) => this._snackBar.open('Not deleted: ' + err.message, 'close'),
            });
    }

    openReprocessDialog(): void {
        const dialogRef = this._dialog.open(ConfirmDialogComponent, {
            data: {
                title: 'Rebuild data from the start time?',
                message:
                    "Stored data from the start time on (or everything, without one) is deleted and rebuilt from the raw metrics with today's context and pricing rules; older data stays as it is. Kafka Cost Control restarts, and until the rebuild is done, data from the start time on is incomplete. Depending on the amount of raw data this takes minutes to hours.",
                confirmLabel: 'Delete and rebuild',
                cancelLabel: 'Cancel',
                destructive: true,
            },
        });

        dialogRef
            .afterClosed()
            .pipe(
                filter(result => result),
                mergeMap(_value => {
                    const startTime = this.startTime?.toISOString();
                    this._snackBar.open('Reprocessing: stopping and resetting…', 'close', {
                        duration: 5000,
                    });
                    return this._mutationReprocess.mutate({ variables: { startTime } });
                })
            )
            .subscribe({
                next: result =>
                    this._snackBar.open(
                        result.data?.reprocess ?? 'Reprocessing requested',
                        'close'
                    ),
                error: (err: Error) => {
                    return this._snackBar.open('Processing failed: ' + err.message, 'close');
                },
            });
    }

    computeDate(negativeHours: number): void {
        this.startTime = new Date(new Date().getTime() - negativeHours * 60 * 60 * 1000);
    }
}
