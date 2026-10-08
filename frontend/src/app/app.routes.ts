import { Routes } from '@angular/router';

export const routes: Routes = [
    {
        path: 'assistant',
        loadComponent: () =>
            import('./assistant/assistant.component').then(m => m.AssistantComponent),
    },
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
        path: 'bills',
        loadComponent: () =>
            import('./bills/bills-list/bills-list.component').then(m => m.BillsListComponent),
    },
    {
        path: 'costs',
        loadComponent: () => import('./costs/cost.component').then(m => m.CostComponent),
    },
    {
        path: 'others',
        loadComponent: () =>
            import('./tab-others/others/others.component').then(m => m.OthersComponent),
    },
    // Renamed/removed pages. Must stay above the catch-alls below: routes match in order.
    {
        path: 'graphs',
        redirectTo: 'explore',
    },
    {
        // removed rather than renamed, so it follows the landing rule
        path: 'home',
        redirectTo: () => landingPath(),
    },
    {
        path: '',
        pathMatch: 'full',
        redirectTo: () => landingPath(),
    },
    {
        path: '**',
        redirectTo: () => landingPath(),
    },
];

/**
 * Where to send someone who did not ask for a particular page. The whole app is behind the
 * sign-in (or open to all), so every page is reachable and this is simply Cost Overview.
 */
export function landingPath(): string {
    return '/costs';
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
    {
        sortOrder: 0,
        path: '/costs',
        label: 'Cost Overview',
        icon: 'attach_money',
        group: 'primary',
    },
    {
        sortOrder: 1,
        path: '/assistant',
        label: 'Assistant',
        icon: 'smart_toy',
        group: 'primary',
    },
    { sortOrder: 2, path: '/explore', label: 'Explore', icon: 'explore', group: 'primary' },
    { sortOrder: 3, path: '/reporting', label: 'Reporting', icon: 'assignment', group: 'primary' },
    { sortOrder: 4, path: '/context-data', label: 'Context Data', icon: 'label', group: 'admin' },
    { sortOrder: 5, path: '/bills', label: 'Bills', icon: 'receipt_long', group: 'admin' },
    {
        sortOrder: 6,
        path: '/pricing-rules',
        label: 'Pricing Rules',
        icon: 'price_check',
        group: 'admin',
    },
    { sortOrder: 7, path: '/others', label: 'Others', icon: 'build', group: 'admin' },
];
