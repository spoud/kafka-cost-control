import { Routes } from '@angular/router';

export const routes: Routes = [
    {
        path: 'home',
        loadComponent: () => import('./tab-home/tab-home.component').then(m => m.TabHomeComponent),
    },
    {
        path: 'graphs',
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
        loadComponent: () => import('./costs/cost.component').then(m => m.CostComponent),
    },
    {
        path: 'others',
        loadComponent: () =>
            import('./tab-others/others/others.component').then(m => m.OthersComponent),
    },
    {
        path: '**',
        redirectTo: '/home',
    },
];

export interface Link {
    path: string;
    label: string;
    icon?: string;
}

export interface HomeLink extends Link {
    helptext: string;
}

export interface NavLink extends Link {
    sortOrder: number;
}

export const homeLinks: HomeLink[] = [
    {
        path: '/costs',
        label: 'Cost Control',
        icon: 'attach_money',
        helptext: 'Control and distribute costs',
    },
    {
        path: '/graphs',
        label: 'Graphs',
        icon: 'bar_chart',
        helptext: 'Get insight into your Kafka usage',
    },
    {
        path: '/reporting',
        label: 'Reporting',
        icon: 'assignment',
        helptext: 'Create and export reports',
    },
    {
        path: '/context-data',
        label: 'Context Data',
        icon: 'label',
        helptext: 'View, add and change context data to enrich metrics',
    },
];

export const menuLinks: NavLink[] = [
    { sortOrder: 0, path: '/home', label: 'Home', icon: 'home' },
    { sortOrder: 2, path: '/graphs', label: 'Graphs', icon: 'bar_chart' },
    { sortOrder: 3, path: '/reporting', label: 'Reporting', icon: 'assignment' },
    { sortOrder: 4, path: '/context-data', label: 'Context Data', icon: 'label' },
    { sortOrder: 5, path: '/pricing-rules', label: 'Pricing Rules', icon: 'price_check' },
];

export const menuLinksLoggedIn: NavLink[] = [
    { sortOrder: 99, path: '/others', label: 'Others' },
    { sortOrder: 1, path: '/costs', label: 'Cost Control', icon: 'attach_money' },
];
