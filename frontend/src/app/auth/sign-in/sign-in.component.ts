import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgOptimizedImage } from '@angular/common';
import { MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MatFormField, MatLabel } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { MatProgressSpinner } from '@angular/material/progress-spinner';
import { AuthService } from '../auth.service';

/**
 * Shown instead of the whole app until the user is signed in: a password form in basic mode, a
 * button to the identity provider in OIDC mode.
 */
@Component({
    selector: 'app-sign-in',
    imports: [
        ReactiveFormsModule,
        NgOptimizedImage,
        MatButton,
        MatIcon,
        MatFormField,
        MatLabel,
        MatInput,
        MatProgressSpinner,
    ],
    templateUrl: './sign-in.component.html',
    styleUrl: './sign-in.component.scss',
})
export class SignInComponent {
    protected readonly auth = inject(AuthService);

    // a FormGroup, so (ngSubmit) fires and the browser doesn't submit (and reload) the page itself
    protected readonly form = new FormGroup({
        username: new FormControl('admin', { nonNullable: true, validators: Validators.required }),
        password: new FormControl('', { nonNullable: true, validators: Validators.required }),
    });
    protected readonly failure = signal<string | null>(null);
    protected readonly busy = signal(false);

    protected async signIn(): Promise<void> {
        if (this.form.invalid || this.busy()) {
            return;
        }
        this.busy.set(true);
        this.failure.set(null);
        try {
            const { username, password } = this.form.getRawValue();
            await this.auth.signInWithPassword(username, password);
        } catch (e) {
            this.failure.set(e instanceof Error ? e.message : String(e));
            this.form.controls.password.reset();
        } finally {
            this.busy.set(false);
        }
    }
}
