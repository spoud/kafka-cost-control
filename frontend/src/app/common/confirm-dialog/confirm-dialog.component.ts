import { Component, inject, TemplateRef } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import {
    MAT_DIALOG_DATA,
    MatDialogActions,
    MatDialogClose,
    MatDialogContent,
    MatDialogTitle,
} from '@angular/material/dialog';
import { MatButton } from '@angular/material/button';

export interface ConfirmDialogData {
    title: string;
    message?: string;
    contentTemplate?: TemplateRef<unknown>;
    templateContext?: unknown;
    confirmLabel?: string;
    cancelLabel?: string;
    destructive?: boolean;
}

@Component({
    selector: 'app-confirm-dialog',
    imports: [
        MatDialogTitle,
        MatDialogContent,
        MatDialogActions,
        MatButton,
        MatDialogClose,
        NgTemplateOutlet,
    ],
    templateUrl: './confirm-dialog.component.html',
})
export class ConfirmDialogComponent {
    data = inject<ConfirmDialogData>(MAT_DIALOG_DATA);
}
