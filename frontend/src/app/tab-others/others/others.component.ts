import {Component} from '@angular/core';
import {ReprocessDialogComponent} from "../reprocess-dialog/reprocess-dialog.component";
import {MatDialog} from "@angular/material/dialog";
import {ReprocessGQL} from "../../../generated/graphql/sdk";
import {MatSnackBar, MatSnackBarModule} from "@angular/material/snack-bar";
import {ApolloError} from '@apollo/client';
import {filter, mergeMap} from 'rxjs';
import {FormsModule} from '@angular/forms';
import {MatButton} from '@angular/material/button';
import {MatDatepicker, MatDatepickerModule, MatDatepickerToggle} from '@angular/material/datepicker';
import {MatInput, MatInputModule} from '@angular/material/input';
import {provideNativeDateAdapter} from '@angular/material/core';
import {
    MatCard,
    MatCardActions,
    MatCardContent,
    MatCardHeader,
    MatCardModule,
    MatCardTitle
} from '@angular/material/card';
import {MatFormField, MatHint, MatLabel} from '@angular/material/form-field';

@Component({
    standalone: true,
    selector: 'app-others',
    templateUrl: './others.component.html',
    styleUrl: './others.component.scss',
    imports: [
        ReprocessDialogComponent,

        FormsModule,

        MatButton,
        MatDatepickerModule,
        MatInputModule,
        MatSnackBarModule,
        MatCardModule
    ],
    providers: [
        provideNativeDateAdapter()
    ],
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
