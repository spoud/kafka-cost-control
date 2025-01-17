import {Component} from '@angular/core';
import {
    MatDialogActions,
    MatDialogClose,
    MatDialogContent,
    MatDialogRef,
    MatDialogTitle
} from "@angular/material/dialog";
import {MatButton} from "@angular/material/button";

@Component({
    selector: 'app-reprocess-dialog',
    imports: [
        MatButton,
        MatDialogTitle,
        MatDialogContent,
        MatDialogActions,
        MatDialogClose,
    ],
    templateUrl: './reprocess-dialog.component.html',
    styleUrl: './reprocess-dialog.component.scss'
})
export class ReprocessDialogComponent {
    constructor(
        public dialogRef: MatDialogRef<ReprocessDialogComponent>
    ) {
    }


    onNoClick(): void {
        this.dialogRef.close();
    }
}
