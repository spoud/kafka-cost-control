import {Component, Inject} from '@angular/core';
import {
    MatDialogActions,
    MatDialogClose,
    MatDialogContent,
    MatDialogRef,
    MatDialogTitle
} from '@angular/material/dialog';
import {MatButton, MatMiniFabButton} from '@angular/material/button';
import {MatError, MatFormField, MatLabel, MatSuffix} from '@angular/material/form-field';
import {MatInput} from '@angular/material/input';
import {MatOption, MatSelect} from '@angular/material/select';
import {
    FormArray,
    FormControl,
    FormGroup,
    NonNullableFormBuilder,
    ReactiveFormsModule,
    Validators
} from '@angular/forms';
import {
    ContextDataSaveRequestInput,
    Entry_String_StringInput,
    SaveContextDataGQL,
} from '../../../generated/graphql/sdk';
import {MatSnackBar} from '@angular/material/snack-bar';
import {MatDatepicker, MatDatepickerInput, MatDatepickerToggle} from '@angular/material/datepicker';
import {EntityType} from '../../../generated/graphql/types';
import {MatDivider} from '@angular/material/divider';
import {MatIcon} from '@angular/material/icon';
import {DateAdapter} from '@angular/material/core';
import {BROWSER_LOCALE} from '../../app.config';

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
        MatError
    ],
    templateUrl: './context-data-create.component.html',
    styleUrl: './context-data-create.component.scss'
})
export class ContextDataCreateComponent {

    saveForm: FormGroup<{
        validFrom: FormControl<Date | null>;
        validUntil: FormControl<Date | null>;
        entityType: FormControl<EntityType | null>;
        regex: FormControl<string | null>;
        context: FormArray<FormControl<Entry_String_StringInput>>;
    }>

    constructor(private contextDataService: SaveContextDataGQL,
                private dialogReg: MatDialogRef<ContextDataCreateComponent>,
                private formBuilder: NonNullableFormBuilder,
                private snackBar: MatSnackBar,
                private dateAdapter: DateAdapter<unknown>,
                @Inject(BROWSER_LOCALE) browserLocale: string,
    ) {
        if (browserLocale) {
            this.dateAdapter.setLocale(browserLocale);
        }
        const today = new Date();
        today.setHours(0, 0, 0, 0);

        this.saveForm = this.formBuilder.group({
            validFrom: new FormControl(today),
            validUntil: new FormControl<Date | null>(null),
            entityType: new FormControl<EntityType>(EntityType.Topic, Validators.required),
            regex: new FormControl<string>('', {validators: Validators.required}),
            context: this.formBuilder.array<Entry_String_StringInput>([], Validators.required),
        });
        this.addKeyValuePair();
    }

    get keyValuePairs(): FormArray {
        return this.saveForm.controls.context;
    }

    addKeyValuePair() {
        const formGroup = this.formBuilder.group({
            key: [undefined, Validators.required],
            value: [undefined, Validators.required]
        });
        this.keyValuePairs.push(formGroup);
    }

    removeKeyValuePair(index: number) {
        this.keyValuePairs.removeAt(index);
    }

    saveDialog() {
        const variables: { request: ContextDataSaveRequestInput } = {
            request: {
                validFrom: this.saveForm.value.validFrom,
                validUntil: this.saveForm.value.validUntil,
                entityType: this.saveForm.value.entityType ?? EntityType.Unknown,
                regex: this.saveForm.value.regex ?? '',
                context: this.saveForm.value.context ?? []
            }
        };
        this.contextDataService.mutate(variables).subscribe({
            next: _ => {
                this.snackBar.open('Context successfully added', 'close', {
                    politeness: 'polite',
                    duration: 2000
                })
                this.dialogReg.close('successfully-saved');
            },
            error: err => {
                this.snackBar.open(`Saving failed: ${err.message}`, 'close');
                this.dialogReg.close('error-from-saving');
            }
        })
    }

    protected readonly EntityType = EntityType;
}
