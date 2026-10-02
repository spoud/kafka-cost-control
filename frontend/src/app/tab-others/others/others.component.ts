import { Component, inject } from '@angular/core';
import { ConfirmDialogComponent } from '../../common/confirm-dialog/confirm-dialog.component';
import { MatDialog } from '@angular/material/dialog';
import { ReprocessGQL } from '../../../generated/graphql/sdk';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { filter, mergeMap } from 'rxjs';
import { FormsModule } from '@angular/forms';
import { MatButton } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatInputModule } from '@angular/material/input';
import { provideNativeDateAdapter } from '@angular/material/core';
import { MatCardModule } from '@angular/material/card';
import { PageHeaderComponent } from '../../common/page-header/page-header.component';

@Component({
    selector: 'app-others',
    templateUrl: './others.component.html',
    styleUrl: './others.component.scss',
    imports: [
        FormsModule,
        MatButton,
        MatDatepickerModule,
        MatInputModule,
        MatSnackBarModule,
        MatCardModule,
        PageHeaderComponent,
    ],
    providers: [provideNativeDateAdapter()],
})
export class OthersComponent {
    private _dialog = inject(MatDialog);
    private _snackBar = inject(MatSnackBar);
    private _mutationReprocess = inject(ReprocessGQL);

    startTime: Date | undefined;

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
