import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {PricingRulesListComponent} from './pricing-rules-list/pricing-rules-list.component';
import {MaterialModule} from '../common/material.module';
import {RouterModule} from '@angular/router';
import {routes} from './tab-pricing-rules.route';


@NgModule({
  declarations: [
    PricingRulesListComponent
  ],
  imports: [
    CommonModule,
    RouterModule.forChild(routes),

    MaterialModule,
  ]
})
export class TabPricingRulesModule {
}
