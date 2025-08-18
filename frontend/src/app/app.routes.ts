import { Routes } from '@angular/router';

export const routes: Routes = [
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
        path: '',
        redirectTo: '/graphs',
        pathMatch: 'full',
    },
];
