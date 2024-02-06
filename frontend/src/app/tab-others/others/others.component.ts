import {Component} from '@angular/core';
import {ReprocessDialogComponent} from "../reprocess-dialog/reprocess-dialog.component";
import {MatDialog} from "@angular/material/dialog";
import {ReprocessGQL} from "../../../generated/graphql/sdk";
import {MatSnackBar} from "@angular/material/snack-bar";
import {ApolloError} from '@apollo/client';
import {filter, mergeMap} from 'rxjs';

@Component({
    selector: 'app-others',
    templateUrl: './others.component.html',
    styleUrl: './others.component.scss'
})
export class OthersComponent {

    startTime: Date | undefined;

    constructor(private _dialog: MatDialog, private _snackBar: MatSnackBar, private _mutationReprocess: ReprocessGQL) {
    }

    openReprocessDialog(): void {
        const dialogRef = this._dialog.open(ReprocessDialogComponent);

        dialogRef.afterClosed().pipe(
            filter(result => result),
            mergeMap(value => {
                let startTime = this.startTime?.toISOString();
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
