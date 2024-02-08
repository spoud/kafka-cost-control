import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {PricingRulesListComponent} from './pricing-rules-list/pricing-rules-list.component';
import {RouterModule} from '@angular/router';


@NgModule({
    imports: [
        CommonModule,
        RouterModule.forChild([
            {
                path: '',
                component: PricingRulesListComponent,
            },
        ]),
    ]
})
export class TabPricingRulesModule {
}
