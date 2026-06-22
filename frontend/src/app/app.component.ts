import { Component, computed, DOCUMENT, effect, inject, signal, Signal } from '@angular/core';
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
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { NgOptimizedImage } from '@angular/common';

type ThemeMode = 'light' | 'system' | 'dark';

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
        MatButtonToggleModule,
        NgOptimizedImage,
    ],
    providers: [provideEchartsCore({ echarts })],
})
export class AppComponent {
    private _dialog = inject(MatDialog);
    private _authService = inject(BasicAuthServiceService);
    private document = inject(DOCUMENT);
    private readonly THEME_MODE_KEY = 'theme-mode';
    private readonly DARK_MEDIA_QUERY = '(prefers-color-scheme: dark)';
    private readonly systemDark = signal<boolean>(window.matchMedia(this.DARK_MEDIA_QUERY).matches);

    isAuthenticated: Signal<boolean>;
    navLinksSignal: Signal<NavLink[]> = computed(() => {
        const list: NavLink[] = [...menuLinks];
        if (this.isAuthenticated()) {
            list.push(...menuLinksLoggedIn);
        }
        return list.sort((a, b) => a.sortOrder - b.sortOrder);
    });
    themeMode = signal<ThemeMode>(this.loadThemeMode());

    constructor() {
        this.isAuthenticated = this._authService.authenticated();

        const prefersColorSchemeDark = window.matchMedia(this.DARK_MEDIA_QUERY);
        prefersColorSchemeDark.addEventListener('change', e => this.systemDark.set(e.matches));

        effect(() => {
            const mode = this.themeMode();
            const isDark = mode === 'dark' || (mode === 'system' && this.systemDark());
            this.document.body.classList.toggle('dark', isDark);
        });
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

    setThemeMode(mode: ThemeMode): void {
        this.themeMode.set(mode);
        localStorage.setItem(this.THEME_MODE_KEY, mode);
    }

    private loadThemeMode(): ThemeMode {
        const stored = localStorage.getItem(this.THEME_MODE_KEY);
        if (stored === 'light' || stored === 'dark' || stored === 'system') return stored;
        return 'system';
    }
}
