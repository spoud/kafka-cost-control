import {Component} from '@angular/core';
import {ReprocessDialogComponent} from "../reprocess-dialog/reprocess-dialog.component";
import {MatDialog} from "@angular/material/dialog";
import {ReprocessGQL} from "../../../generated/graphql/sdk";
import {MatSnackBar} from "@angular/material/snack-bar";

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

    dialogRef.afterClosed().subscribe(result => {
      let startTime = this.startTime?.toISOString();
      console.log('The dialog was closed', result, startTime);
      if (result) {
        this._snackBar.open("Reprocessing started", "Close", {
          duration: 5000,
        });
        this._mutationReprocess.mutate({
          startTime
        }).subscribe((result) => {
          console.log('Reprocessing result', result);
        })
      }
    });
  }

  computeDate(negativeHours: number): void {
    this.startTime = new Date(new Date().getTime() - negativeHours * 60 * 60 * 1000);
  }
}
