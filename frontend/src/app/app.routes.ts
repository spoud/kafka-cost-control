import {Routes} from '@angular/router';

export const routes: Routes = [
    {
        path: 'context-data',
        loadChildren: () => import('./tab-context-data/tab-context-data.module').then(m => m.TabContextDataModule)
    },
    {
        path: 'pricing-rules',
        loadChildren: () => import('./tab-pricing-rules/tab-pricing-rules.module').then(m => m.TabPricingRulesModule)
    },
    {
        path: 'others',
        loadChildren: () => import('./tab-others/tab-others.module').then(m => m.TabOthersModule)
    },
    {
        path: '',
        redirectTo: '/context-data',
        pathMatch: 'full'
    }
];
