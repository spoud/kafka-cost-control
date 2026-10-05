import { Component, computed, inject, Signal, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { MatToolbar } from '@angular/material/toolbar';
import { MatIcon } from '@angular/material/icon';
import { MatIconButton } from '@angular/material/button';
import { MatMenu, MatMenuItem, MatMenuTrigger } from '@angular/material/menu';
import { AuthService } from './auth/auth.service';
import { SignInComponent } from './auth/sign-in/sign-in.component';
import { MatTooltip } from '@angular/material/tooltip';
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
import { landingPath, NavLink, menuLinks } from './app.routes';
import { AssistantStatusService } from './assistant/assistant-status.service';
import { NgOptimizedImage } from '@angular/common';
import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { toSignal } from '@angular/core/rxjs-interop';
import { map } from 'rxjs';
import { ThemeService } from './services/theme.service';
import { readRaw, writeRaw } from './common/persisted-state';

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
        SignInComponent,
    ],
    providers: [provideEchartsCore({ echarts })],
})
export class AppComponent {
    protected readonly auth = inject(AuthService);
    private _breakpointObserver = inject(BreakpointObserver);
    protected readonly themeService = inject(ThemeService);

    private readonly SIDENAV_COLLAPSED_KEY = 'sidenav-collapsed';

    isHandset: Signal<boolean> = toSignal(
        this._breakpointObserver.observe(Breakpoints.Handset).pipe(map(result => result.matches)),
        { initialValue: this._breakpointObserver.isMatched(Breakpoints.Handset) }
    );

    private readonly assistantStatus = inject(AssistantStatusService);

    navLinksSignal: Signal<NavLink[]> = computed(() => {
        const list: NavLink[] = [...menuLinks];
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
    collapsed = signal<boolean>(readRaw(this.SIDENAV_COLLAPSED_KEY) === 'true');

    // the logo follows the same rule as the router's default route
    protected readonly homeLink = landingPath;

    signOut(): void {
        this.auth.signOut();
    }

    toggleDarkMode(): void {
        this.themeService.setDark(!this.themeService.isDark());
    }

    toggleCollapsed(): void {
        const next = !this.collapsed();
        this.collapsed.set(next);
        writeRaw(this.SIDENAV_COLLAPSED_KEY, String(next));
    }
}
