import { Component, computed, inject, Signal, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatToolbar } from '@angular/material/toolbar';
import { MatIcon } from '@angular/material/icon';
import { MatIconButton } from '@angular/material/button';
import { MatMenu, MatMenuItem, MatMenuTrigger } from '@angular/material/menu';
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
import {
    MatListItem,
    MatListItemIcon,
    MatListItemMeta,
    MatListItemTitle,
    MatNavList,
} from '@angular/material/list';
import { MatDivider } from '@angular/material/divider';
import { MatSlideToggle } from '@angular/material/slide-toggle';
import { NavLink, menuLinks, menuLinksLoggedIn } from './app.routes';
import { AssistantStatusService } from './assistant/assistant-status.service';
import { NgOptimizedImage } from '@angular/common';
import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { ThemeService } from './services/theme.service';

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
        MatIconButton,
        MatMenu,
        MatMenuItem,
        MatMenuTrigger,
        MatTooltip,
        MatSidenavContainer,
        MatSidenavContent,
        MatSidenav,
        MatNavList,
        MatListItem,
        MatListItemIcon,
        MatListItemTitle,
        MatListItemMeta,
        MatDivider,
        MatSlideToggle,
        RouterOutlet,
        NgOptimizedImage,
    ],
    providers: [provideEchartsCore({ echarts })],
})
export class AppComponent {
    private _dialog = inject(MatDialog);
    private _authService = inject(BasicAuthServiceService);
    private _breakpointObserver = inject(BreakpointObserver);
    protected readonly themeService = inject(ThemeService);

    private readonly SIDENAV_COLLAPSED_KEY = 'sidenav-collapsed';

    constructor() {
        this.isAuthenticated = this._authService.authenticated();
    }

    isHandset: Signal<boolean> = toSignal(
        this._breakpointObserver.observe(Breakpoints.Handset).pipe(map(result => result.matches)),
        { initialValue: this._breakpointObserver.isMatched(Breakpoints.Handset) }
    );

    isAuthenticated: Signal<boolean>;
    private readonly assistantStatus = inject(AssistantStatusService);

    navLinksSignal: Signal<NavLink[]> = computed(() => {
        const list: NavLink[] = [...menuLinks];
        if (this.isAuthenticated()) {
            list.push(...menuLinksLoggedIn);
        }
        // The assistant is optional and off by default. Hide it unless the backend reports it can
        // actually answer, rather than offering a chat box that fails on the first question.
        const assistantAvailable = this.assistantStatus.available();
        return list
            .filter(link => link.path !== '/assistant' || assistantAvailable)
            .sort((a, b) => a.sortOrder - b.sortOrder);
    });
    primaryNavLinks: Signal<NavLink[]> = computed(() =>
        this.navLinksSignal().filter(link => link.group === 'primary')
    );
    adminNavLinks: Signal<NavLink[]> = computed(() =>
        this.navLinksSignal().filter(link => link.group === 'admin')
    );
    collapsed = signal<boolean>(localStorage.getItem(this.SIDENAV_COLLAPSED_KEY) === 'true');

    signOut(): void {
        this._authService.signOut();
    }

    toggleDarkMode(): void {
        this.themeService.setThemeMode(this.themeService.isDark() ? 'light' : 'dark');
    }

    signIn(): void {
        const dialogRef = this._dialog.open(SignInDialogComponent);

        dialogRef.afterClosed().subscribe({
            next: result => console.log('Sign in dialog closed', result),
        });
    }

    toggleCollapsed(): void {
        const next = !this.collapsed();
        this.collapsed.set(next);
        localStorage.setItem(this.SIDENAV_COLLAPSED_KEY, String(next));
    }
}
