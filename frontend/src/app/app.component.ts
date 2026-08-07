import { Component, computed, DOCUMENT, effect, inject, signal, Signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatToolbar } from '@angular/material/toolbar';
import { MatIcon } from '@angular/material/icon';
import { MatButton, MatIconButton } from '@angular/material/button';
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
import { MatDivider } from '@angular/material/divider';
import { NavLink, menuLinks, menuLinksLoggedIn } from './app.routes';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { NgOptimizedImage } from '@angular/common';
import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';

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
        MatToolbar,
        MatIcon,
        MatButton,
        MatIconButton,
        MatTooltip,
        MatSidenavContainer,
        MatSidenavContent,
        MatSidenav,
        MatNavList,
        MatListItem,
        MatDivider,
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
    private _breakpointObserver = inject(BreakpointObserver);
    private readonly THEME_MODE_KEY = 'theme-mode';
    private readonly DARK_MEDIA_QUERY = '(prefers-color-scheme: dark)';
    private readonly systemDark = signal<boolean>(window.matchMedia(this.DARK_MEDIA_QUERY).matches);

    isHandset: Signal<boolean> = toSignal(
        this._breakpointObserver.observe(Breakpoints.Handset).pipe(map(result => result.matches)),
        { initialValue: this._breakpointObserver.isMatched(Breakpoints.Handset) }
    );

    private readonly SIDENAV_COLLAPSED_KEY = 'sidenav-collapsed';

    isAuthenticated: Signal<boolean>;
    navLinksSignal: Signal<NavLink[]> = computed(() => {
        const list: NavLink[] = [...menuLinks];
        if (this.isAuthenticated()) {
            list.push(...menuLinksLoggedIn);
        }
        return list.sort((a, b) => a.sortOrder - b.sortOrder);
    });
    primaryNavLinks: Signal<NavLink[]> = computed(() =>
        this.navLinksSignal().filter(link => link.group === 'primary')
    );
    adminNavLinks: Signal<NavLink[]> = computed(() =>
        this.navLinksSignal().filter(link => link.group === 'admin')
    );
    themeMode = signal<ThemeMode>(this.loadThemeMode());
    collapsed = signal<boolean>(localStorage.getItem(this.SIDENAV_COLLAPSED_KEY) === 'true');

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

    toggleCollapsed(): void {
        const next = !this.collapsed();
        this.collapsed.set(next);
        localStorage.setItem(this.SIDENAV_COLLAPSED_KEY, String(next));
    }

    private loadThemeMode(): ThemeMode {
        const stored = localStorage.getItem(this.THEME_MODE_KEY);
        if (stored === 'light' || stored === 'dark' || stored === 'system') return stored;
        return 'system';
    }
}
