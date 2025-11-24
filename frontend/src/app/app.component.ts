import { Component, computed, DOCUMENT, inject, Signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatTabsModule } from '@angular/material/tabs';
import { MatToolbar } from '@angular/material/toolbar';
import { MatIcon } from '@angular/material/icon';
import { MatButton } from '@angular/material/button';
import { BasicAuthServiceService } from './auth/basic-auth-service.service';
import { MatTooltip } from '@angular/material/tooltip';
import { MatDialog } from '@angular/material/dialog';
import { SignInDialogComponent } from './common/sign-in-dialog/sign-in-dialog.component';
import { provideEchartsCore } from 'ngx-echarts';
import * as echarts from 'echarts/core';
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import {
    DatasetComponent,
    DataZoomComponent,
    GridComponent,
    LegendComponent,
    TooltipComponent,
} from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { MatSidenav, MatSidenavContainer, MatSidenavContent } from '@angular/material/sidenav';
import { MatListItem, MatNavList } from '@angular/material/list';
import { NavLink, menuLinks, menuLinksLoggedIn } from './app.routes';
import { MatSlideToggle } from '@angular/material/slide-toggle';
import { NgOptimizedImage } from '@angular/common';

echarts.use([
    LineChart,
    BarChart,
    GridComponent,
    CanvasRenderer,
    LegendComponent,
    PieChart,
    TooltipComponent,
    DatasetComponent,
    DataZoomComponent,
]);

@Component({
    selector: 'app-root',
    templateUrl: './app.component.html',
    styleUrl: './app.component.scss',
    imports: [
        RouterLink,
        RouterLinkActive,
        MatTabsModule,
        MatToolbar,
        MatIcon,
        MatButton,
        MatTooltip,
        MatSidenavContainer,
        MatSidenavContent,
        MatSidenav,
        MatNavList,
        MatListItem,
        RouterOutlet,
        MatSlideToggle,
        NgOptimizedImage,
    ],
    providers: [provideEchartsCore({ echarts })],
})
export class AppComponent {
    private _dialog = inject(MatDialog);
    private _authService = inject(BasicAuthServiceService);
    private document = inject(DOCUMENT);

    isAuthenticated: Signal<boolean>;
    navLinksSignal: Signal<NavLink[]> = computed(() => {
        const list: NavLink[] = [...menuLinks];
        if (this.isAuthenticated()) {
            list.push(...menuLinksLoggedIn);
        }
        return list.sort((a, b) => a.sortOrder - b.sortOrder);
    });

    constructor() {
        this.isAuthenticated = this._authService.authenticated();
    }

    signOut(): void {
        this._authService.signOut();
    }

    signIn(): void {
        const dialogRef = this._dialog.open(SignInDialogComponent);

        dialogRef.afterClosed().subscribe({
            next: result => console.log('Sign in dialog closed', result),
        });
    }

    onThemeChange() {
        this.document.body.classList.toggle('dark');
    }
}
