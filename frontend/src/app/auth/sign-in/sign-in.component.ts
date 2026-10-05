import { Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgOptimizedImage } from '@angular/common';
import { MatButton, MatIconButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MatFormField, MatLabel, MatSuffix } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { MatProgressSpinner } from '@angular/material/progress-spinner';
import { AuthService } from '../auth.service';

/** Providers Quarkus knows by name, shown as "Continue with …". */
const PROVIDER_NAMES: Record<string, string> = {
    google: 'Google',
    microsoft: 'Microsoft',
    github: 'GitHub',
    gitlab: 'GitLab',
    apple: 'Apple',
    slack: 'Slack',
};

/** "spoud.io", "a.com or b.com", "a.com, b.com or c.com". */
export function listDomains(domains: string[]): string {
    if (domains.length <= 1) {
        return domains[0] ?? '';
    }
    return `${domains.slice(0, -1).join(', ')} or ${domains[domains.length - 1]}`;
}

/**
 * Shown instead of the whole app until the user is signed in: a password form in basic mode, a
 * button to the identity provider in OIDC mode, and what to do when the account has no access or
 * the aggregator can't be reached.
 */
@Component({
    selector: 'app-sign-in',
    imports: [
        ReactiveFormsModule,
        NgOptimizedImage,
        MatButton,
        MatIconButton,
        MatIcon,
        MatFormField,
        MatLabel,
        MatSuffix,
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
    protected readonly showPassword = signal(false);
    protected readonly redirecting = signal(false);

    protected readonly providerName = computed(() => {
        const provider = this.auth.provider();
        return provider ? (PROVIDER_NAMES[provider] ?? null) : null;
    });
    protected readonly accountHint = computed(() => {
        const domains = this.auth.domains();
        return domains.length
            ? `Use your ${listDomains(domains)} account.`
            : 'Use your organisation account.';
    });

    protected continueWithProvider(): void {
        this.redirecting.set(true);
        this.auth.signInWithProvider();
    }

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
