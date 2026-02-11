import { Component, inject } from '@angular/core';
import {
    MAT_DIALOG_DATA,
    MatDialogActions,
    MatDialogClose,
    MatDialogContent,
    MatDialogRef,
    MatDialogTitle,
} from '@angular/material/dialog';
import { MatButton, MatMiniFabButton } from '@angular/material/button';
import { MatError, MatFormField, MatLabel, MatSuffix } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { MatOption, MatSelect } from '@angular/material/select';
import {
    FormArray,
    FormControl,
    FormGroup,
    NonNullableFormBuilder,
    ReactiveFormsModule,
    Validators,
} from '@angular/forms';
import {
    ContextDataSaveRequestInput,
    EntityType,
    Entry_String_String,
    Entry_String_StringInput,
    SaveContextDataGQL,
} from '../../../generated/graphql/sdk';
import { MatSnackBar } from '@angular/material/snack-bar';
import {
    MatDatepicker,
    MatDatepickerInput,
    MatDatepickerToggle,
} from '@angular/material/datepicker';
import { MatDivider } from '@angular/material/divider';
import { MatIcon } from '@angular/material/icon';
import { DateAdapter } from '@angular/material/core';
import { BROWSER_LOCALE } from '../../app.config';

@Component({
    selector: 'app-context-data-create',
    imports: [
        MatDialogTitle,
        MatDialogContent,
        MatDialogActions,
        MatDialogClose,
        MatButton,
        MatFormField,
        MatLabel,
        MatInput,
        MatSelect,
        MatOption,
        ReactiveFormsModule,
        MatDatepickerInput,
        MatDatepickerToggle,
        MatSuffix,
        MatDatepicker,
        MatDivider,
        MatIcon,
        MatMiniFabButton,
        MatError,
    ],
    templateUrl: './context-data-save.component.html',
    styleUrl: './context-data-save.component.scss',
})
export class ContextDataSaveComponent {
    private contextDataService = inject(SaveContextDataGQL);
    private dialogRef = inject<MatDialogRef<ContextDataSaveComponent>>(MatDialogRef);
    private formBuilder = inject(NonNullableFormBuilder);
    private snackBar = inject(MatSnackBar);
    private dateAdapter = inject<DateAdapter<unknown>>(DateAdapter);
    private data = inject<{ element: ContextDataEntity } | null>(MAT_DIALOG_DATA, {
        optional: true,
    });

    saveForm: FormGroup<{
        validFrom: FormControl<Date | null>;
        validUntil: FormControl<Date | null>;
        entityType: FormControl<EntityType | null>;
        regex: FormControl<string | null>;
        context: FormArray<FormControl<Entry_String_StringInput>>;
    }>;

    constructor() {
        const browserLocale = inject(BROWSER_LOCALE);
        const data = this.data;

        if (browserLocale) {
            this.dateAdapter.setLocale(browserLocale);
        }
        const today = new Date();
        today.setHours(0, 0, 0, 0);

        if (data) {
            // edit mode, we have existing data
            this.saveForm = this.formBuilder.group({
                validFrom: new FormControl(data.element.validFrom),
                validUntil: new FormControl<Date | null>(data.element.validUntil),
                entityType: new FormControl<EntityType>(
                    data.element.entityType,
                    Validators.required
                ),
                regex: new FormControl<string>(data.element.regex, {
                    validators: Validators.required,
                }),
                context: this.formBuilder.array<Entry_String_StringInput>([], Validators.required),
            });
            this.addExistingKeyValuePairs(data.element.context);
        } else {
            // creation mode, use sensible default values
            this.saveForm = this.formBuilder.group({
                validFrom: new FormControl(today),
                validUntil: new FormControl<Date | null>(null),
                entityType: new FormControl<EntityType>(EntityType.Topic, Validators.required),
                regex: new FormControl<string>('', { validators: Validators.required }),
                context: this.formBuilder.array<Entry_String_StringInput>([], Validators.required),
            });
            this.addKeyValuePair();
        }
    }

    get keyValuePairs(): FormArray {
        return this.saveForm.controls.context;
    }

    addKeyValuePair() {
        const formGroup = this.formBuilder.group({
            key: [undefined, Validators.required],
            value: [undefined, Validators.required],
        });
        this.keyValuePairs.push(formGroup);
    }

    private addExistingKeyValuePairs(context: Array<Entry_String_String>) {
        context.forEach(keyValue => {
            const formGroup = this.formBuilder.group({
                key: [keyValue.key, Validators.required],
                value: [keyValue.value, Validators.required],
            });
            this.keyValuePairs.push(formGroup);
        });
    }

    removeKeyValuePair(index: number) {
        this.keyValuePairs.removeAt(index);
    }

    saveDialog() {
        const variables: { request: ContextDataSaveRequestInput } = {
            request: {
                id: this.data?.element.id ?? undefined,
                validFrom: this.saveForm.value.validFrom,
                validUntil: this.saveForm.value.validUntil,
                entityType: this.saveForm.value.entityType ?? EntityType.Unknown,
                regex: this.saveForm.value.regex ?? '',
                context: this.saveForm.value.context ?? [],
            },
        };
        this.contextDataService.mutate({ variables }).subscribe({
            next: _ => {
                this.snackBar.open('Context successfully saved', 'close', {
                    politeness: 'polite',
                    duration: 2000,
                });
                this.dialogRef.close('successfully-saved');
            },
            error: err => {
                this.snackBar.open(`Saving failed: ${err.message}`, 'close');
                this.dialogRef.close('error-from-saving');
            },
        });
    }

    protected readonly EntityType = EntityType;
}
