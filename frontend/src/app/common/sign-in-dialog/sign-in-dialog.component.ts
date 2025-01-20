import {Component} from '@angular/core';
import {MatButton} from "@angular/material/button";
import {MatDialogContent, MatDialogTitle} from "@angular/material/dialog";
import {FormControl, FormsModule, ReactiveFormsModule, Validators} from '@angular/forms';
import {MatFormField, MatHint, MatLabel, MatSuffix} from '@angular/material/form-field';
import {MatInput} from '@angular/material/input';
import {BasicAuthServiceService} from '../../services/basic-auth-service.service';
import {MatSnackBar, MatSnackBarModule} from '@angular/material/snack-bar';
import {DialogRef} from '@angular/cdk/dialog';

@Component({
    selector: 'app-sign-in-dialog',
    imports: [
        ReactiveFormsModule,
        MatSnackBarModule,
        MatButton,
        MatDialogTitle,
        MatDialogContent,
        FormsModule,
        MatFormField,
        MatHint,
        MatInput,
        MatLabel,
        MatSuffix,
    ],
    templateUrl: './sign-in-dialog.component.html',
    styleUrl: './sign-in-dialog.component.scss'
})
export class SignInDialogComponent {

    constructor(private _dialogRef: DialogRef<SignInDialogComponent>, private _authService: BasicAuthServiceService, private _snakbar: MatSnackBar) {
    }

    username = new FormControl('', [Validators.required]);
    password = new FormControl('', [Validators.required]);

    signIn() {
        this._authService.signIn(this.username.value || '', this.password.value || '').subscribe({
            next: (_result) => {
                this._snakbar.open('Sign in success', 'close', {
                    politeness: 'polite',
                    duration: 2000,
                });
                this._dialogRef.close();
            },
            error: (err) => {
                this._snakbar.open('Sign in failed: ' + err.message, 'close', {
                    politeness: 'assertive',
                    duration: 5000,
                });
            }
        });
    }
}
