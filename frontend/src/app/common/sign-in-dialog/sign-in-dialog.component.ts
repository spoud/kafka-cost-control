import { Component, inject } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { MatDialogContent, MatDialogRef, MatDialogTitle } from '@angular/material/dialog';
import { FormControl, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatFormField, MatLabel } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { BasicAuthServiceService } from '../../auth/basic-auth-service.service';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

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
        MatInput,
        MatLabel,
    ],
    templateUrl: './sign-in-dialog.component.html',
    styleUrl: './sign-in-dialog.component.scss',
})
export class SignInDialogComponent {
    // MatDialogRef, not the CDK DialogRef: MatDialog provides the Material token, so injecting
    // the CDK one yields null and close() throws, leaving the dialog open after a successful login.
    private _dialogRef = inject<MatDialogRef<SignInDialogComponent>>(MatDialogRef);
    private _authService = inject(BasicAuthServiceService);
    private _snakbar = inject(MatSnackBar);

    username = new FormControl('', [Validators.required]);
    password = new FormControl('', [Validators.required]);

    signIn() {
        this._authService.signIn(this.username.value || '', this.password.value || '').subscribe({
            next: _result => {
                this._snakbar.open('Sign in success', 'close', {
                    politeness: 'polite',
                    duration: 2000,
                });
                this._dialogRef.close();
            },
            error: err => {
                this._snakbar.open('Sign in failed: ' + err.message, 'close', {
                    politeness: 'assertive',
                    duration: 5000,
                });
            },
        });
    }
}
