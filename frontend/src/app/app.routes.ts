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
    helptext?: string;
}

export const homeLinks: Link[] = [
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
        helptext: 'Create and download reports',
    },
    {
        path: '/context-data',
        label: 'Context Data',
        icon: 'label',
        helptext: 'View and change context data.',
    },
    {
        path: '/pricing-rules',
        label: 'Pricing Rules',
        icon: 'price_check',
        helptext: 'Pricing Rules determine costs from the gathered metrics',
    },
];

export const menuLinks: Link[] = [
    { path: '/home', label: 'Home', icon: 'home' },
    { path: '/graphs', label: 'Graphs', icon: 'bar_chart' },
    { path: '/reporting', label: 'Reporting', icon: 'assignment' },
    { path: '/context-data', label: 'Context Data', icon: 'label' },
    { path: '/pricing-rules', label: 'Pricing Rules', icon: 'price_check' },
];

export const menuLinksLoggedIn: Link[] = [{ path: '/others', label: 'Others' }];
