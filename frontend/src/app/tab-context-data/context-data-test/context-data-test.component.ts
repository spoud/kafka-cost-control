import { Component, inject, signal } from '@angular/core';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { KeyValueListComponent } from '../../common/key-value-list/key-value-list.component';
import { MatButton } from '@angular/material/button';
import { MatFormField, MatLabel } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { TestContextGQL, TestContextQuery } from '../../../generated/graphql/sdk';
import { EntityType, Entry_String_String } from '../../../generated/graphql/types';
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
    pending = signal(false);

    previousInput = signal<string | undefined>(undefined);
    matchedTopicsContext = signal<Array<Entry_String_String> | undefined>(undefined);
    matchedPrincipalContext = signal<Array<Entry_String_String> | undefined>(undefined);

    testContext() {
        if (!this.testString) {
            return;
        }
        this.pending.set(true);
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
                this.pending.set(false);
            });
    }

    private unwrap(data: TestContextQuery) {
        const topics: Array<Entry_String_String> = [];
        const principals: Array<Entry_String_String> = [];
        this.previousInput.set(this.testString);
        data.contextTest.forEach(context => {
            if (context.entityType === EntityType.Topic) {
                topics.push(...context.context);
            } else if (context.entityType === EntityType.Principal) {
                principals.push(...context.context);
            }
        });
        this.matchedTopicsContext.set(topics);
        this.matchedPrincipalContext.set(principals);
    }

    reset() {
        this.matchedTopicsContext.set(undefined);
        this.matchedPrincipalContext.set(undefined);
        this.previousInput.set(undefined);
    }
}
