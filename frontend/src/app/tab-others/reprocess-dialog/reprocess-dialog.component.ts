import {Component} from '@angular/core';
import {
    MatDialogActions,
    MatDialogClose,
    MatDialogContent,
    MatDialogRef,
    MatDialogTitle
} from "@angular/material/dialog";
import {MatButtonModule} from "@angular/material/button";
import {FormsModule} from "@angular/forms";
import {MatInputModule} from "@angular/material/input";
import {MatFormFieldModule} from "@angular/material/form-field";

@Component({
    selector: 'app-reprocess-dialog',
    standalone: true,
    imports: [
        MatFormFieldModule,
        MatInputModule,
        FormsModule,
        MatButtonModule,
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
