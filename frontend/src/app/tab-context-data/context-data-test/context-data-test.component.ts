import { Component, inject } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { KeyValueListComponent } from '../../common/key-value-list/key-value-list.component';
import { MatButton } from '@angular/material/button';
import { MatFormField, MatLabel } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import {
    EntityType,
    Entry_String_String,
    TestContextGQL,
    TestContextQuery,
} from '../../../generated/graphql/sdk';
import { MatSnackBar } from '@angular/material/snack-bar';
import {
    MatDialogActions,
    MatDialogClose,
    MatDialogContent,
    MatDialogTitle,
} from '@angular/material/dialog';
import { MatDivider } from '@angular/material/divider';
import { MatTab, MatTabGroup } from '@angular/material/tabs';

@Component({
    selector: 'app-context-data-test',
    imports: [
        FormsModule,
        KeyValueListComponent,
        MatButton,
        MatFormField,
        MatInput,
        MatLabel,
        ReactiveFormsModule,
        MatDialogContent,
        MatDialogActions,
        MatDialogClose,
        MatDialogTitle,
        MatDivider,
        MatTabGroup,
        MatTab,
    ],
    templateUrl: './context-data-test.component.html',
    styleUrl: './context-data-test.component.scss',
})
export class ContextDataTestComponent {
    private contextTester = inject(TestContextGQL);
    private _snackBar = inject(MatSnackBar);

    testString?: string;
    pending = false;

    previousInput?: string;
    matchedTopicsContext?: Array<Entry_String_String>;
    matchedPrincipalContext?: Array<Entry_String_String>;

    testContext() {
        if (!this.testString) {
            return;
        }
        this.pending = true;
        this.contextTester
            .fetch({ variables: { testString: this.testString } })
            .subscribe({
                next: result => {
                    if (result.error) {
                        this._snackBar.open(
                            `Could not test contexts. ${result.error.message}`,
                            'close'
                        );
                    } else if (result.data) {
                        this.unwrap(result.data);
                    }
                },
                error: err =>
                    this._snackBar.open(`Could not test contexts. ${err.message}`, 'close'),
            })
            .add(() => {
                this.pending = false;
            });
    }

    private unwrap(data: TestContextQuery) {
        this.matchedTopicsContext = [];
        this.matchedPrincipalContext = [];
        this.previousInput = this.testString;
        data.contextTest.forEach(context => {
            if (context.entityType === EntityType.Topic) {
                this.matchedTopicsContext?.push(...context.context);
            } else if (context.entityType === EntityType.Principal) {
                this.matchedPrincipalContext?.push(...context.context);
            }
        });
    }

    reset() {
        this.matchedTopicsContext = undefined;
        this.matchedPrincipalContext = undefined;
        this.previousInput = undefined;
    }
}
