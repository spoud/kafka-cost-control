import {Component, Inject} from '@angular/core';
import {
    MAT_DIALOG_DATA,
    MatDialogActions,
    MatDialogClose,
    MatDialogContent,
    MatDialogTitle
} from '@angular/material/dialog';
import {MatButton} from '@angular/material/button';
import {ContextDataEntity} from '../../../generated/graphql/types';
import {KeyValueListComponent} from '../../common/key-value-list/key-value-list.component';

@Component({
    selector: 'app-confirm-dialog',
    imports: [
        MatDialogTitle,
        MatDialogContent,
        MatDialogActions,
        MatButton,
        MatDialogClose,
        KeyValueListComponent
    ],
    templateUrl: './confirm-dialog.component.html',
})
export class ConfirmDialogComponent {

    constructor(@Inject(MAT_DIALOG_DATA) public data: { element: ContextDataEntity }) {
    }
}
