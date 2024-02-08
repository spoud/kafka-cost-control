import {Component, computed, Signal} from '@angular/core';
import {RouterLink, RouterLinkActive, RouterOutlet} from '@angular/router';
import {MatTabsModule} from '@angular/material/tabs';
import {MatToolbar} from '@angular/material/toolbar';
import {MatIcon} from '@angular/material/icon';
import {MatButton} from '@angular/material/button';
import {BasicAuthServiceService} from './services/basic-auth-service.service';
import {MatTooltip} from '@angular/material/tooltip';
import {MatDialog} from '@angular/material/dialog';
import {SignInDialogComponent} from './common/sign-in-dialog/sign-in-dialog.component';

interface Link {
    path: string;
    label: string;
}

@Component({
    selector: 'app-root',
    standalone: true,
    templateUrl: './app.component.html',
    styleUrl: './app.component.scss',
    imports: [
        RouterLink,
        RouterLinkActive,
        RouterOutlet,

        MatTabsModule,
        MatToolbar,
        MatIcon,
        MatButton,
        MatTooltip
    ],
})
export class AppComponent {
    isAuthenticated: Signal<boolean>;
    navLinksSignal: Signal<Link[]> = computed(() => {
        const list = [
            {path: '/context-data', label: 'Context Data'},
            {path: '/pricing-rules', label: 'Pricing Rules'},
        ];
        if (this.isAuthenticated()) {
            list.push({path: '/others', label: 'Others'});

        }
        return list;
    })

    constructor(private _dialog: MatDialog, private _authService: BasicAuthServiceService) {
        this.isAuthenticated = _authService.authenticated();
    }

    signOut(): void {
        this._authService.signOut();
    }

    signIn(): void {
        const dialogRef = this._dialog.open(SignInDialogComponent);

        dialogRef.afterClosed().subscribe({
            next: (result) => console.log('Sign in dialog closed', result)
        });
    }
}
