import { Component, effect, inject } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { MatIcon } from '@angular/material/icon';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { SignInDialogComponent } from '../sign-in-dialog/sign-in-dialog.component';
import { BasicAuthServiceService } from '../../auth/basic-auth-service.service';

@Component({
    selector: 'app-unauthorized',
    imports: [MatButton, MatIcon, RouterLink],
    templateUrl: './unauthorized.component.html',
    styleUrl: './unauthorized.component.scss',
})
export class UnauthorizedComponent {
    private _dialog = inject(MatDialog);
    private _authService = inject(BasicAuthServiceService);
    private _route = inject(ActivatedRoute);
    private _router = inject(Router);

    private isAuthenticated = this._authService.authenticated();

    constructor() {
        // Once the user signs in, continue to the page they originally requested
        effect(() => {
            if (this.isAuthenticated()) {
                const returnUrl = this._route.snapshot.queryParamMap.get('returnUrl') ?? '/home';
                void this._router.navigateByUrl(returnUrl);
            }
        });
    }

    signIn(): void {
        this._dialog.open(SignInDialogComponent);
    }
}
