import {Component} from '@angular/core';
import {ReprocessDialogComponent} from "../reprocess-dialog/reprocess-dialog.component";
import {MatDialog} from "@angular/material/dialog";
import {ReprocessGQL} from "../../../generated/graphql/sdk";
import {MatSnackBar, MatSnackBarModule} from "@angular/material/snack-bar";
import {ApolloError} from '@apollo/client';
import {filter, mergeMap} from 'rxjs';
import {FormsModule} from '@angular/forms';
import {MatButton} from '@angular/material/button';
import {MatDatepickerModule} from '@angular/material/datepicker';
import {MatInputModule} from '@angular/material/input';
import {provideNativeDateAdapter} from '@angular/material/core';
import {MatCardModule} from '@angular/material/card';

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
        MatCardModule
    ],
    providers: [
        provideNativeDateAdapter()
    ]
})
export class OthersComponent {

    startTime: Date | undefined;

    constructor(private _dialog: MatDialog, private _snackBar: MatSnackBar, private _mutationReprocess: ReprocessGQL) {
    }

    openReprocessDialog(): void {
        const dialogRef = this._dialog.open(ReprocessDialogComponent);

        dialogRef.afterClosed().pipe(
            filter(result => result),
            mergeMap(_value => {
                const startTime = this.startTime?.toISOString();
                this._snackBar.open("Reprocessing started", "close", {
                    duration: 5000,
                });
                return this._mutationReprocess.mutate({startTime})
            })
        ).subscribe({
            next: (result) => console.log('Reprocessing result', result),
            error: (err: ApolloError) => this._snackBar.open("Processing failed: " + err.message, "close")
        });
    }

    computeDate(negativeHours: number): void {
        this.startTime = new Date(new Date().getTime() - negativeHours * 60 * 60 * 1000);
    }
}
