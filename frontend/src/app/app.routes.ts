import { inject } from '@angular/core';
import { Routes } from '@angular/router';
import { loggedInGuard } from './auth/logged-in.guard';
import { BasicAuthServiceService } from './auth/basic-auth-service.service';

export const routes: Routes = [
    {
        path: 'explore',
        loadComponent: () =>
            import('./tab-graphs/tab-graphs.component').then(m => m.TabGraphsComponent),
    },
    {
        path: 'reporting',
        loadComponent: () =>
            import('./tab-reporting/tab-reporting.component').then(m => m.TabReportingComponent),
    },
    {
        path: 'context-data',
        loadComponent: () =>
            import('./tab-context-data/context-data-list/context-data-list.component').then(
                m => m.ContextDataListComponent
            ),
    },
    {
        path: 'pricing-rules',
        loadComponent: () =>
            import('./tab-pricing-rules/pricing-rules-list/pricing-rules-list.component').then(
                m => m.PricingRulesListComponent
            ),
    },
    {
        path: 'costs',
        canActivate: [loggedInGuard],
        loadComponent: () => import('./costs/cost.component').then(m => m.CostComponent),
    },
    {
        path: 'others',
        canActivate: [loggedInGuard],
        loadComponent: () =>
            import('./tab-others/others/others.component').then(m => m.OthersComponent),
    },
    {
        path: 'unauthorized',
        loadComponent: () =>
            import('./common/unauthorized/unauthorized.component').then(
                m => m.UnauthorizedComponent
            ),
    },
    {
        path: '',
        pathMatch: 'full',
        redirectTo: () => landingRoute(),
    },
    {
        path: '**',
        redirectTo: () => landingRoute(),
    },
];

/**
 * Where to send someone who did not ask for a particular page.
 *
 * Cost Overview is behind {@link loggedInGuard} and authentication lives in sessionStorage, so
 * redirecting there unconditionally sent every first visit, new tab and post-session refresh to
 * the "Sign in required" page — an error page as the app's front door. Explore is public and
 * shows real data, so it is the right landing for anyone not signed in.
 */
function landingRoute(): string {
    return inject(BasicAuthServiceService).authenticated()() ? '/costs' : '/explore';
}

export interface Link {
    path: string;
    label: string;
    icon?: string;
}

export type NavGroup = 'primary' | 'admin';

export interface NavLink extends Link {
    sortOrder: number;
    group: NavGroup;
}

export const menuLinks: NavLink[] = [
    { sortOrder: 1, path: '/explore', label: 'Explore', icon: 'explore', group: 'primary' },
    { sortOrder: 2, path: '/reporting', label: 'Reporting', icon: 'assignment', group: 'primary' },
    { sortOrder: 3, path: '/context-data', label: 'Context Data', icon: 'label', group: 'admin' },
    {
        sortOrder: 4,
        path: '/pricing-rules',
        label: 'Pricing Rules',
        icon: 'price_check',
        group: 'admin',
    },
];

export const menuLinksLoggedIn: NavLink[] = [
    {
        sortOrder: 0,
        path: '/costs',
        label: 'Cost Overview',
        icon: 'attach_money',
        group: 'primary',
    },
    { sortOrder: 5, path: '/others', label: 'Others', icon: 'build', group: 'admin' },
];
