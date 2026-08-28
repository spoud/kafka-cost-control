import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
    MatDialogActions,
    MatDialogContent,
    MatDialogRef,
    MatDialogTitle,
} from '@angular/material/dialog';
import { MatButton } from '@angular/material/button';
import { MatFormField, MatLabel } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';

@Component({
    selector: 'app-save-config-dialog',
    imports: [
        FormsModule,
        MatDialogTitle,
        MatDialogContent,
        MatDialogActions,
        MatButton,
        MatFormField,
        MatLabel,
        MatInput,
    ],
    templateUrl: './save-config-dialog.component.html',
    styleUrl: './save-config-dialog.component.scss',
})
export class SaveConfigDialogComponent {
    private dialogRef = inject<MatDialogRef<SaveConfigDialogComponent>>(MatDialogRef);

    name = '';

    save(): void {
        if (this.name.trim()) {
            this.dialogRef.close(this.name.trim());
        }
    }
}
